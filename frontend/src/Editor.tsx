import { useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api, errorText, getImage, json, title, type Managed, type Vehicle } from './api';
import { Empty, Photo, Problem, useQuery } from './ui';
import { Result } from './Account';

const defaults: Vehicle = {
  make: '', model: '', trim: '', year: new Date().getFullYear(), mileage: 0, mileageUnit: 'mi',
  transmission: 'Manual', fuel: 'Gasoline', bodyStyle: 'Coupe', exterior: '', interior: '',
  location: '', description: '', condition: '', serviceHistory: '', images: [],
};
type TextField = [keyof Vehicle, string, number];
const identity: TextField[] = [['make', 'Make', 50], ['model', 'Model', 50], ['trim', 'Trim', 60],
  ['exterior', 'Exterior color', 40], ['interior', 'Interior color', 40], ['location', 'Location', 100]];
const notes: TextField[] = [['description', 'Description', 5000], ['condition', 'Condition notes', 3000], ['serviceHistory', 'Service history', 3000]];
const choices: [keyof Vehicle, string, string[]][] = [
  ['mileageUnit', 'Mileage unit', ['mi', 'km']], ['transmission', 'Transmission', ['Manual', 'Automatic', 'Dual-clutch', 'CVT']],
  ['fuel', 'Fuel', ['Gasoline', 'Diesel', 'Hybrid', 'Electric', 'Other']],
  ['bodyStyle', 'Body style', ['Coupe', 'Convertible', 'Sedan', 'Hatchback', 'SUV', 'Wagon', 'Truck', 'Other']],
];
function local(value: string) {
  const time = new Date(value);
  return new Date(time.getTime() - time.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}
export function Editor() {
  const { id } = useParams();
  return <main className="wrap page">
    <Link className="back" to="/dashboard">Your listings</Link><h1>{id ? 'Edit listing' : 'Create a listing'}</h1>
    {id ? <Existing id={id} /> : <ListingForm />}
  </main>;
}
function Existing({ id }: { id: string }) {
  const query = useQuery<Managed[]>('/api/seller/auctions');
  const item = query.data?.find(item => item.auction.id === id);
  return <Result query={query}>{item && ['DRAFT', 'REJECTED'].includes(item.auction.status) ?
    <ListingForm key={id} item={item} /> : <Empty>This draft is unavailable.</Empty>}</Result>;
}
function ListingForm({ item }: { item?: Managed }) {
  const auction = item?.auction;
  const [vehicle, setVehicle] = useState<Vehicle>(auction?.vehicle ?? defaults);
  const [previews, setPreviews] = useState<Record<string, string>>({});
  const [problem, setProblem] = useState('');
  const [busy, setBusy] = useState(false);
  const [preview, setPreview] = useState(false);
  const navigate = useNavigate();
  useEffect(() => {
    let active = true;
    const urls: string[] = [];
    async function resolve(image: string) {
      if (!image.startsWith('/api/')) return;
      try {
        const url = URL.createObjectURL(await getImage(image));
        if (!active) return URL.revokeObjectURL(url);
        urls.push(url);
        setPreviews(current => ({ ...current, [image]: url }));
      } catch { /* The placeholder preserves the gallery order. */ }
    }
    vehicle.images.forEach(image => { void resolve(image); });
    return () => { active = false; urls.forEach(url => URL.revokeObjectURL(url)); };
  }, [vehicle.images]);
  function field(key: keyof Vehicle, value: string | number | string[]) { setVehicle(current => ({ ...current, [key]: value })); }
  async function upload(files: FileList | null) {
    if (!files?.length) return;
    if (vehicle.images.length + files.length > 12) return setProblem('A listing can include up to 12 images.');
    setBusy(true); setProblem('');
    try {
      for (const file of files) {
        const form = new FormData();
        form.append('file', file);
        const { url } = await api<{ url: string }>('/api/seller/images', { method: 'POST', body: form });
        setVehicle(previous => ({ ...previous, images: [...previous.images, url] }));
      }
    } catch (error) { setProblem(errorText(error)); }
    finally { setBusy(false); }
  }
  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setProblem(''); setBusy(true);
    const values = new FormData(event.currentTarget);
    try {
      const result = await api<Managed>('/api/seller/auctions' + (auction ? '/' + auction.id : ''), json({
        vehicle, startingPrice: values.get('startingPrice'), increment: values.get('increment'), reserve: values.get('reserve') || null,
        startsAt: new Date(String(values.get('startsAt'))).toISOString(), endsAt: new Date(String(values.get('endsAt'))).toISOString(),
      }, auction ? 'PUT' : 'POST'));
      if ((event.nativeEvent as SubmitEvent).submitter?.getAttribute('value') === 'submit') {
        try { await api('/api/seller/auctions/' + result.auction.id + '/submit', { method: 'POST' }); }
        catch (error) {
          if (!auction) navigate('/seller/edit/' + result.auction.id, { replace: true });
          throw error;
        }
      }
      navigate('/dashboard');
    } catch (error) { setProblem(errorText(error)); }
    finally { setBusy(false); }
  }
  function photo(image: string) { return image.startsWith('/api/') ? previews[image] : image; }
  return <form className="listing-form" onSubmit={save}>
    <section className="paper"><h2>Vehicle specifications</h2><div className="form-grid">
      {identity.map(([key, label, max]) => <label key={key}>{label}<input required value={String(vehicle[key])} maxLength={max} onChange={event => field(key, event.target.value)} /></label>)}
      <label>Model year<input type="number" min="1886" max="2100" value={vehicle.year || ''} required onChange={event => field('year', Number(event.target.value))} /></label>
      <label>Mileage<input type="number" min="0" max="9999999" value={vehicle.mileage} required onChange={event => field('mileage', Number(event.target.value))} /></label>
      {choices.map(([key, label, values]) => <label key={key}>{label}<select value={String(vehicle[key])} onChange={event => field(key, event.target.value)}>
        {[...new Set([String(vehicle[key]), ...values])].map(value => <option key={value}>{value}</option>)}</select></label>)}
    </div></section>
    <section className="paper"><h2>Condition and history</h2><div className="stack">{notes.map(([key, label, max]) =>
      <label key={key}>{label}<textarea rows={4} required maxLength={max} value={String(vehicle[key])} onChange={event => field(key, event.target.value)} /></label>)}</div></section>
    <section className="paper"><h2>Photo gallery</h2>
      <label>Upload JPEG or PNG<input name="photos" type="file" accept="image/jpeg,image/png" multiple disabled={busy}
        onChange={event => { void upload(event.target.files); event.target.value = ''; }} /></label>
      <p className="muted">The first photo is the cover. Add up to 12 photos, 8 MB each.</p>
      <div className="editor-gallery">{vehicle.images.map((image, index) => <div key={image}>
        <Photo src={photo(image)} alt={'Vehicle image ' + (index + 1)} />
        <div className="actions">{index === 0 ? <span>Cover</span> : <button type="button" className="text-button" onClick={() =>
          field('images', [image, ...vehicle.images.filter(value => value !== image)])}>Make cover</button>}
          <button type="button" className="text-button" onClick={() => field('images', vehicle.images.filter(value => value !== image))}>Remove</button></div></div>)}</div>
    </section>
    <section className="paper"><h2>Pricing and schedule</h2><div className="form-grid">
      <label>Opening bid<input name="startingPrice" type="number" step=".01" min=".01" defaultValue={auction?.startingPrice} required /></label>
      <label>Minimum increment<input name="increment" type="number" step=".01" min=".01" defaultValue={auction?.increment ?? 250} required /></label>
      <label>Private reserve (optional)<input name="reserve" type="number" step=".01" min=".01" defaultValue={item?.reserve ?? ''} /></label>
      <label>Starts (local time)<input name="startsAt" type="datetime-local" defaultValue={auction && local(auction.startsAt)} required /></label>
      <label>Ends (local time)<input name="endsAt" type="datetime-local" defaultValue={auction && local(auction.endsAt)} required /></label>
    </div></section>
    {preview && <section className="paper preview"><Photo src={photo(vehicle.images[0] ?? '')} alt="Listing cover preview" /><div><h2>{title(vehicle)}</h2><p>{vehicle.location}</p><p>{vehicle.description}</p></div></section>}
    {problem && <Problem message={problem} />}
    <div className="actions"><button type="button" className="button outline" aria-expanded={preview} onClick={() => setPreview(!preview)}>{preview ? 'Hide preview' : 'Preview listing'}</button>
      <button className="button outline" disabled={busy} value="draft">Save draft</button><button className="button dark" disabled={busy} value="submit">Submit for review</button></div>
  </form>;
}
