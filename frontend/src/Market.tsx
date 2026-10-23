import { useEffect, useState, type FormEvent } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { api, dateTime, errorText, json, money, title, type Auction, type AuctionState, type Page } from './api';
import { Cards, Empty, Loading, Photo, Problem, Status, timeLeft, useAuth, useQuery } from './ui';

export function Home() {
  const live = useQuery<Page>('/api/auctions?status=live&size=4');
  const sold = useQuery<Page>('/api/auctions?status=sold&sort=recent&size=3');
  const featured = live.data?.items.find(item => item.vehicle.images.length > 0);
  return <main>
    <section className="hero">
      <div className="wrap hero-grid">
        <div className="hero-content"><p className="eyebrow">VEHICLE AUCTIONS · PORTFOLIO DEMO</p>
          <h1>Find your<br />next <em>drive.</em></h1>
          <p>Discover collector cars, modern performance icons and distinctive daily drivers. Bid live on the one that belongs in your garage.</p>
          <Link to="/browse" className="button gold">Explore auctions</Link>
        </div>
        <Link className="hero-photo" to={featured ? '/auctions/' + featured.id : '/browse'}>
          <Photo src={featured?.vehicle.images[0] ?? '/cars/porsche.jpg'} alt={featured ? title(featured.vehicle) : 'Illustrative sports car'} />
          {featured && <div className="hero-caption"><Status status={featured.status} /><strong>{title(featured.vehicle)}</strong></div>}
        </Link>
      </div>
    </section>
    <div className="wrap market-home">
      <section><div className="section-title"><h2>Ending soon</h2><Link to="/browse?status=live">All live auctions</Link></div>
        {live.loading ? <Loading /> : live.error ? <Problem message={live.error} retry={live.reload} /> : <Cards items={live.data?.items.slice(0, 3) ?? []} />}</section>
      <section><div className="section-title"><h2>Recently sold</h2><Link to="/browse?status=sold">Sold auctions</Link></div>
        {sold.loading ? <Loading /> : sold.error ? <Problem message={sold.error} retry={sold.reload} /> : <Cards items={sold.data?.items ?? []} />}</section>
    </div>
  </main>;
}

export function Browse() {
  const [params, setParams] = useSearchParams();
  const query = useQuery<Page>('/api/auctions?' + params.toString());
  function apply(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const next = new URLSearchParams();
    new FormData(event.currentTarget).forEach((value, key) => { if (String(value).trim()) next.set(key, String(value).trim()); });
    setParams(next);
  }
  function page(value: number) {
    const next = new URLSearchParams(params);
    next.set('page', String(value));
    setParams(next);
  }
  return <main className="wrap page">
    <p className="eyebrow">THE MARKETPLACE</p><h1>Browse auctions</h1>
    <form className="filters" onSubmit={apply} key={['q', 'make', 'status', 'sort', 'bodyStyle', 'maxPrice'].map(key => params.get(key)).join('|')}>
      <label>Search<input name="q" defaultValue={params.get('q') ?? ''} placeholder="Make, model or location" maxLength={100} /></label>
      <label>Make<input name="make" defaultValue={params.get('make') ?? ''} placeholder="Any make" maxLength={50} /></label>
      <label>Body style<select name="bodyStyle" defaultValue={params.get('bodyStyle') ?? ''}><option value="">Any style</option>
        {['Coupe', 'Convertible', 'Sedan', 'Hatchback', 'SUV', 'Wagon', 'Truck'].map(value => <option key={value}>{value}</option>)}</select></label>
      <label>Status<select name="status" defaultValue={params.get('status') ?? ''}><option value="">All statuses</option><option value="live">Live</option><option value="upcoming">Scheduled</option><option value="sold">Sold</option></select></label>
      <label>Max price<input name="maxPrice" type="number" min="0" defaultValue={params.get('maxPrice') ?? ''} placeholder="Any price" /></label>
      <label>Sort<select name="sort" defaultValue={params.get('sort') ?? 'ending'}><option value="ending">Ending first</option><option value="recent">Recently updated</option><option value="price">Lowest price</option></select></label>
      <button className="button dark">Apply</button>
    </form>
    {query.loading ? <Loading /> : query.error ? <Problem message={query.error} retry={query.reload} /> : query.data && <>
      <p className="muted">{query.data.total} {query.data.total === 1 ? 'auction' : 'auctions'}</p><Cards items={query.data.items} empty="No auctions match these filters." />
      {query.data.total > query.data.size && <div className="pagination"><button className="button outline" onClick={() => page(query.data!.page - 1)} disabled={query.data.page === 0}>Previous</button>
        <span>Page {query.data.page + 1} of {Math.ceil(query.data.total / query.data.size)}</span>
        <button className="button outline" onClick={() => page(query.data!.page + 1)} disabled={(query.data.page + 1) * query.data.size >= query.data.total}>Next</button></div>}
    </>}
  </main>;
}

function useLive(id: string) {
  const [state, setState] = useState<AuctionState>();
  const [connection, setConnection] = useState('Connecting');
  const [error, setError] = useState('');
  const [offset, setOffset] = useState(0);
  const [attempt, retry] = useState(0);
  useEffect(() => {
    let socket: WebSocket | undefined;
    let timer: ReturnType<typeof setTimeout>;
    let watchdog: ReturnType<typeof setTimeout>;
    let closed = false;
    let version = -1;
    let retries = 0;
    setState(undefined); setError(''); setConnection('Connecting');

    function apply(next: AuctionState) {
      if (closed || next.auction.version < version) return;
      version = next.auction.version;
      setState(next);
      setOffset(new Date(next.auction.serverTime).valueOf() - Date.now());
    }
    async function synchronize() {
      apply(await api<AuctionState>('/api/auctions/' + id + '/state'));
    }
    function connect() {
      if (closed) return;
      const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
      socket = new WebSocket(scheme + '//' + location.host + '/live/auctions/' + id);
      socket.onmessage = event => {
        const next = JSON.parse(event.data) as AuctionState;
        clearTimeout(watchdog);
        watchdog = setTimeout(() => socket?.close(), 18000);
        retries = 0;
        if (next.auction.version > version + 1) {
          setConnection('Syncing');
          synchronize().then(() => { if (!closed && socket?.readyState === WebSocket.OPEN) setConnection('Live updates'); })
            .catch(() => socket?.close());
        } else {
          apply(next);
          setConnection('Live updates');
        }
      };
      watchdog = setTimeout(() => socket?.close(), 18000);
      socket.onclose = () => {
        if (closed) return;
        clearTimeout(watchdog);
        setConnection('Disconnected');
        synchronize().catch(() => undefined);
        timer = setTimeout(connect, Math.min(8000, 750 * 2 ** Math.min(retries++, 4)));
      };
    }
    synchronize().then(connect).catch(reason => { if (!closed) setError(errorText(reason)); });
    return () => { closed = true; clearTimeout(timer); clearTimeout(watchdog); socket?.close(); };
  }, [id, attempt]);
  return { state, connection, error, offset, retry: () => retry(value => value + 1) };
}

export function Detail() {
  const { id = '' } = useParams();
  return <AuctionDetail key={id} id={id} />;
}

function AuctionDetail({ id }: { id: string }) {
  const { state, connection, error, offset, retry } = useLive(id);
  const { session } = useAuth();
  const [image, setImage] = useState(0);
  const [now, setNow] = useState(Date.now());
  const [watching, setWatching] = useState(false);
  const [watchError, setWatchError] = useState('');
  const [watchBusy, setWatchBusy] = useState(false);
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 500);
    return () => clearInterval(timer);
  }, []);
  useEffect(() => {
    if (session?.profile.role !== 'BUYER') return;
    let active = true;
    api<Auction[]>('/api/buyer/watchlist').then(items => { if (active) setWatching(items.some(item => item.id === id)); })
      .catch(() => undefined);
    return () => { active = false; };
  }, [id, session?.profile.id, session?.profile.role]);
  async function toggleWatch() {
    setWatchBusy(true); setWatchError('');
    try {
      await api('/api/buyer/watchlist/' + id, { method: watching ? 'DELETE' : 'PUT' });
      setWatching(!watching);
    } catch (reason) { setWatchError(errorText(reason)); }
    finally { setWatchBusy(false); }
  }
  if (error) return <main className="wrap page"><Problem message={error} retry={retry} /></main>;
  if (!state) return <main className="wrap page"><Loading /></main>;
  const { auction, bids } = state;
  const { vehicle } = auction;
  const status = auction.status === 'LIVE' && now + offset >= Date.parse(auction.endsAt) ? 'FINALIZING' : auction.status;
  const canBid = status === 'LIVE' && connection === 'Live updates';
  return <main className="wrap page">
    <Link className="back" to="/browse">Browse auctions</Link>
    <div className="detail-title"><div><p className="eyebrow">{vehicle.location}</p><h1>{title(vehicle)}</h1></div>
      {session?.profile.role === 'BUYER' && <button className="button outline" onClick={toggleWatch} disabled={watchBusy}>{watching ? 'Remove from watchlist' : 'Watch auction'}</button>}</div>
    {watchError && <Problem message={watchError} />}
    <div className="detail-grid"><div>
      <div className="gallery"><Photo src={vehicle.images[image]} alt={title(vehicle) + ' photo ' + (image + 1)} /></div>
      {vehicle.images.length > 1 && <div className="thumbnails">{vehicle.images.map((src, index) =>
        <button aria-label={'Show photo ' + (index + 1)} aria-pressed={image === index} key={src} onClick={() => setImage(index)}><Photo src={src} alt="" /></button>)}</div>}
      {vehicle.images.some(src => src.startsWith('/cars/')) && <p className="muted">Illustrative photos. Vehicle details and sale results are fictional.</p>}<section className="detail-section"><h2>Vehicle details</h2><dl className="specs">
        {Object.entries({
          Mileage: vehicle.mileage.toLocaleString() + ' ' + vehicle.mileageUnit, Transmission: vehicle.transmission,
          Fuel: vehicle.fuel, 'Body style': vehicle.bodyStyle, Exterior: vehicle.exterior, Interior: vehicle.interior,
          Location: vehicle.location, Seller: auction.seller,
        }).map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}
      </dl><p>{vehicle.description}</p><h3>Condition</h3><p>{vehicle.condition}</p><h3>Service history</h3><p>{vehicle.serviceHistory}</p></section>
    </div><aside>
      <section className="bid-panel">
        <div className="bid-top"><Status status={status} /><span className="connection" role="status">{connection}</span></div>
        <p className="muted">{status === 'SOLD' ? 'Sold for' : auction.hasBids ? 'Current bid' : 'Opening bid'}</p>
        <strong className="big-price">{money(auction.price, auction.currency)}</strong>
        <div className="bid-meta"><span>{auction.reserveMet ? 'Reserve requirement met' : 'Reserve not met'}</span>
          {status === 'LIVE' && <strong>{timeLeft(auction.endsAt, now + offset)} left</strong>}</div>
        {status === 'SCHEDULED' && <p>Opens {dateTime(auction.startsAt)}</p>}
        {status === 'FINALIZING' && <p>Closing the auction and confirming the result.</p>}
        {status === 'LIVE' && (session?.profile.role === 'BUYER' ? <BidForm auction={auction} enabled={canBid} /> :
          !session ? <Link className="button dark" state={{ from: '/auctions/' + id }} to="/login">Sign in to bid</Link> : null)}
      </section>
      <section className="detail-section"><h2>Bid history</h2>{bids.length ?
        <ol className="bid-history">{bids.map(bid => <li key={bid.version}><div>{bid.bidder}<small>{dateTime(bid.placedAt)}</small></div><strong>{money(bid.price, auction.currency)}</strong></li>)}</ol> :
        <Empty>No bids yet.</Empty>}</section>
    </aside></div>
  </main>;
}

function BidForm({ auction, enabled }: { auction: Auction; enabled: boolean }) {
  const [mode, setMode] = useState('bids');
  const [amount, setAmount] = useState('');
  const [pending, setPending] = useState<{ amount: string; key: string; mode: string }>();
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const minimum = Math.round((auction.price + (auction.hasBids ? auction.increment : 0)) * 100) / 100;
  async function confirm() {
    if (!pending || !enabled) return;
    setSubmitting(true); setError('');
    try {
      const result = await api<{ leading: boolean; extended: boolean }>('/api/auctions/' + auction.id + '/' + pending.mode,
        { ...json({ amount: pending.amount }), headers: { 'Idempotency-Key': pending.key } });
      setMessage((result.leading ? 'Bid accepted. You are leading.' : 'Bid accepted. Another bidder is leading.') +
        (result.extended ? ' The deadline was extended.' : ''));
      setPending(undefined); setAmount('');
    } catch (reason) { setError(errorText(reason)); }
    finally { setSubmitting(false); }
  }
  return <>
    <form className="bid-form" onSubmit={event => { event.preventDefault(); setMessage(''); setError(''); setPending({ amount, mode, key: crypto.randomUUID() }); }}>
      <label>Bid type<select value={mode} onChange={event => { setMode(event.target.value); setPending(undefined); }}>
        <option value="bids">Manual bid</option><option value="max-bid">Private maximum</option></select></label>
      {mode === 'max-bid' && <small>Your limit stays private. The system raises your bid only as needed. An earlier equal maximum has priority.</small>}
      <label>Amount ({auction.currency})<input name="bidAmount" type="number" min={mode === 'bids' ? minimum : auction.price}
        max="999999999999.99" step=".01" required placeholder={mode === 'bids' ? String(minimum) : String(auction.price)}
        value={amount} onChange={event => { setAmount(event.target.value); setPending(undefined); }} /></label>
      <button className="button dark" disabled={!enabled || submitting}>Review bid</button>
    </form>
    {!enabled && <p className="muted">Bidding is paused while live updates reconnect.</p>}
    {pending && <div className="confirm"><strong>{pending.mode === 'bids' ? 'Confirm bid of ' : 'Confirm private maximum of '}{money(Number(pending.amount), auction.currency)}</strong>
      <p>A bid in the final 30 seconds extends the auction. If you win and the reserve is met, an order is created.</p>
      <div className="actions"><button className="button gold" onClick={confirm} disabled={!enabled || submitting}>Confirm bid</button>
        <button className="text-button" onClick={() => setPending(undefined)} disabled={submitting}>Back</button></div></div>}
    {message && <p role="status" className="success">{message}</p>}{error && <Problem message={error} />}
  </>;
}
