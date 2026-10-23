import { useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { api, dateTime, errorText, json, money, setSession, title, type Auction, type Managed, type Notice, type Order, type Profile, type Session } from './api';
import { Cards, Empty, Loading, Problem, Status, useAuth, useQuery } from './ui';

export function AuthForm({ register = false }: { register?: boolean }) {
  const navigate = useNavigate();
  const location = useLocation();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError('');
    const values = Object.fromEntries(new FormData(event.currentTarget));
    try {
      setSession(await api<Session>(register ? '/api/auth/register' : '/api/auth/login', json(values)));
      navigate(location.state?.from || '/dashboard', { replace: true });
    } catch (reason) { setError(errorText(reason)); }
    finally { setBusy(false); }
  }
  return <main className="wrap page"><div className="form-shell">
    <p className="eyebrow">DIAMOND CAR AUCTION</p><h1>{register ? 'Create an account' : 'Sign in'}</h1>
    <form className="stack" onSubmit={submit}>
      {register && <label>Name<input name="name" autoComplete="name" required maxLength={80} /></label>}
      <label>Email<input name="email" type="email" autoComplete="email" required maxLength={254} /></label>
      <label>Password<input name="password" type="password" autoComplete={register ? 'new-password' : 'current-password'}
        minLength={register ? 12 : undefined} maxLength={72} required /></label>
      {register && <label>Account type<select name="role"><option value="BUYER">Buyer</option><option value="SELLER">Seller</option></select></label>}
      {error && <Problem message={error} />}
      <button className="button dark" disabled={busy}>{register ? 'Register' : 'Sign in'}</button>
    </form>
    <p>{register ? 'Already registered?' : 'New to Diamond?'} <Link to={register ? '/login' : '/register'}>{register ? 'Sign in' : 'Create an account'}</Link></p>
  </div></main>;
}
export function ProfilePage() {
  const { session } = useAuth();
  const [error, setError] = useState('');
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);
  if (!session) return null;
  const { profile } = session;
  async function update(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError(''); setSaved(false);
    try {
      const profile = await api<Profile>('/api/profile', json(Object.fromEntries(new FormData(event.currentTarget)), 'PUT'));
      setSession({ ...session!, profile });
      setSaved(true);
    } catch (reason) { setError(errorText(reason)); }
    finally { setBusy(false); }
  }
  return <main className="wrap page"><div className="form-shell"><h1>Your profile</h1><p>{profile.email} · {profile.role.toLowerCase()}</p>
    <form className="stack" onSubmit={update}><label>Display name<input name="name" defaultValue={profile.name} required maxLength={80} /></label>
      {error && <Problem message={error} />}{saved && <p className="success" role="status">Profile updated.</p>}
      <button className="button dark" disabled={busy}>Save profile</button></form></div></main>;
}
export function Dashboard() {
  const role = useAuth().session?.profile.role;
  if (role === 'SELLER') return <Seller />;
  if (role === 'ADMIN') return <Moderation />;
  return <Buyer />;
}

type Position = { auction: Auction; leading: boolean; won: boolean };
function Buyer() {
  const positions = useQuery<Position[]>('/api/buyer/bids');
  const watches = useQuery<Auction[]>('/api/buyer/watchlist');
  const orders = useQuery<Order[]>('/api/orders');
  const [tab, setTab] = useState('Active bids');
  useEffect(() => {
    if (tab !== 'Orders' && tab !== 'Won auctions') return;
    const timer = setInterval(orders.reload, 7000);
    return () => clearInterval(timer);
  }, [tab]);
  const active = positions.data?.filter(position => ['LIVE', 'FINALIZING'].includes(position.auction.status)) ?? [];
  const won = positions.data?.filter(position => position.won) ?? [];
  return <main className="wrap page"><p className="eyebrow">BUYER DASHBOARD</p><h1>Your garage</h1>
    <div className="tabs" aria-label="Dashboard views">{['Active bids', 'Watchlist', 'Won auctions', 'Orders'].map(value =>
      <button key={value} aria-pressed={tab === value} onClick={() => setTab(value)}>{value}</button>)}</div>
    {tab === 'Watchlist' && <Result query={watches}><Cards items={watches.data ?? []} empty="Your watchlist is empty." /></Result>}
    {tab === 'Active bids' && <Result query={positions}><EntryList empty="You have no active bids.">{active.map(position =>
      <AuctionEntry key={position.auction.id} auction={position.auction} secondary={position.leading ? 'You are leading' : 'Outbid'} />)}</EntryList></Result>}
    {tab === 'Won auctions' && <Result query={positions}><EntryList empty="You have no wins yet.">{won.map(position => {
      const order = orders.data?.find(order => order.auctionId === position.auction.id);
      return <AuctionEntry key={position.auction.id} auction={position.auction} secondary={order ?
        <Link to={'/orders/' + order.id}>View order</Link> : 'Order is being prepared'} />;
    })}</EntryList></Result>}
    {tab === 'Orders' && <Result query={orders}><EntryList empty="Orders appear here after you win an auction.">{(orders.data ?? []).map(order =>
      <li className="entry" key={order.id}><Link to={'/orders/' + order.id}>{order.title}</Link><span>{money(order.amount, order.currency)}</span><Status status={order.status} /></li>)}</EntryList></Result>}
  </main>;
}
function Seller() {
  const query = useQuery<Managed[]>('/api/seller/auctions');
  return <main className="wrap page"><div className="detail-title"><div><p className="eyebrow">SELLER DASHBOARD</p><h1>Your listings</h1></div><Link className="button dark" to="/seller/new">Create listing</Link></div>
    <Result query={query}><EntryList empty="Create a listing to get started.">{(query.data ?? []).map(item => <SellerEntry key={item.auction.id} item={item} reload={query.reload} />)}</EntryList></Result>
  </main>;
}
function SellerEntry({ item, reload }: { item: Managed; reload: () => void }) {
  const { auction } = item;
  const [error, setError] = useState('');
  const [cancel, setCancel] = useState(false);
  const [busy, setBusy] = useState(false);
  const editable = ['DRAFT', 'REJECTED'].includes(auction.status);
  async function action(path: string, body?: unknown) {
    setBusy(true); setError('');
    try { await api('/api/seller/auctions/' + auction.id + '/' + path, body ? json(body) : { method: 'POST' }); setCancel(false); reload(); }
    catch (reason) { setError(errorText(reason)); }
    finally { setBusy(false); }
  }
  return <li className="entry entry-manage"><div><strong>{title(auction.vehicle)}</strong>{item.reason && <p>{item.reason}</p>}</div>
    <Status status={auction.status} /><div className="actions">
      {editable && <Link to={'/seller/edit/' + auction.id}>Edit draft</Link>}
      {auction.status === 'DRAFT' && <button className="button outline compact" disabled={busy} onClick={() => action('submit')}>Submit for review</button>}
      {!editable && auction.status !== 'REVIEW' && <Link to={'/auctions/' + auction.id}>View auction</Link>}
      {['LIVE', 'SCHEDULED'].includes(auction.status) && !auction.hasBids && <button className="text-button" onClick={() => setCancel(!cancel)}>Cancel auction</button>}
    </div>{cancel && <ReasonForm label="Confirm cancellation" busy={busy} onSubmit={reason => action('cancel', { reason })} />}{error && <Problem message={error} />}</li>;
}
function Moderation() {
  const query = useQuery<Managed[]>('/api/admin/auctions');
  const [onlyQueue, setOnlyQueue] = useState(true);
  const items = (query.data ?? []).filter(item => !onlyQueue || item.auction.status === 'REVIEW');
  return <main className="wrap page"><p className="eyebrow">ADMINISTRATION</p><h1>Listing moderation</h1>
    <div className="tabs"><button aria-pressed={onlyQueue} onClick={() => setOnlyQueue(true)}>Review queue</button>
      <button aria-pressed={!onlyQueue} onClick={() => setOnlyQueue(false)}>All moderation</button></div>
    <Result query={query}><EntryList empty="The moderation queue is clear.">{items.map(item => <ReviewEntry key={item.auction.id} item={item} reload={query.reload} />)}</EntryList></Result>
  </main>;
}
function ReviewEntry({ item, reload }: { item: Managed; reload: () => void }) {
  const [reasonFor, setReasonFor] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [open, setOpen] = useState(false);
  const { auction } = item;
  async function action(path: string, body: unknown) {
    setBusy(true); setError('');
    try { await api('/api/admin/auctions/' + auction.id + '/' + path, json(body)); setReasonFor(''); reload(); }
    catch (reason) { setError(errorText(reason)); }
    finally { setBusy(false); }
  }
  return <li className="entry entry-manage"><div><strong>{title(auction.vehicle)}</strong><small>{auction.seller} · {dateTime(auction.startsAt)}</small></div>
    <Status status={auction.status} /><div className="actions"><button className="text-button" onClick={() => setOpen(!open)}>{open ? 'Hide listing' : 'Review listing'}</button>
      {auction.status === 'REVIEW' && <><button className="button dark compact" onClick={() => action('review', { decision: 'APPROVE' })} disabled={busy}>Approve</button>
        <button className="button outline compact" onClick={() => setReasonFor('reject')}>Reject</button></>}
      {['LIVE', 'SCHEDULED'].includes(auction.status) && <button className="button outline compact" onClick={() => setReasonFor('cancel')}>Cancel auction</button>}</div>
    {open && <div className="moderation-preview"><p>{auction.vehicle.description}</p><p>{auction.vehicle.condition}</p><p>{auction.vehicle.serviceHistory}</p>
      <p>Opening bid {money(auction.startingPrice, auction.currency)} · Reserve {item.reserve ? money(item.reserve, auction.currency) : 'None'}</p></div>}
    {reasonFor && <ReasonForm label={reasonFor === 'reject' ? 'Reject listing' : 'Confirm cancellation'} busy={busy} onSubmit={reason =>
      reasonFor === 'reject' ? action('review', { decision: 'REJECT', reason }) : action('cancel', { reason })} />}
    {error && <Problem message={error} />}
  </li>;
}
function ReasonForm({ label, busy, onSubmit }: { label: string; busy: boolean; onSubmit: (reason: string) => void }) {
  return <form className="reason" onSubmit={event => { event.preventDefault(); onSubmit(String(new FormData(event.currentTarget).get('reason'))); }}>
    <label>Reason<input name="reason" required maxLength={500} /></label><button className="button dark compact" disabled={busy}>{label}</button></form>;
}
export function Notifications() {
  const query = useQuery<Notice[]>('/api/notifications');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState('');
  useEffect(() => {
    const timer = setInterval(query.reload, 10000);
    return () => clearInterval(timer);
  }, []);
  async function mark(notice: Notice) {
    setBusy(notice.id); setError('');
    try { await api('/api/notifications/' + notice.id, json({ read: !notice.readAt }, 'PUT')); query.reload(); }
    catch (reason) { setError(errorText(reason)); }
    finally { setBusy(''); }
  }
  return <main className="wrap page"><h1>Notifications</h1>{error && <Problem message={error} />}
    <Result query={query}><EntryList empty="You’re all caught up.">{(query.data ?? []).map(notice =>
      <li key={notice.id} className={'entry notice ' + (notice.readAt ? '' : 'unread')}><div><Link to={notice.link}>{notice.title}</Link><p>{notice.body}</p>
        <small>{dateTime(notice.createdAt)}</small></div><button className="button outline compact" disabled={busy === notice.id} onClick={() => mark(notice)}>
          {notice.readAt ? 'Mark unread' : 'Mark read'}</button></li>)}</EntryList></Result>
  </main>;
}
export function OrderDetail() {
  const { id } = useParams();
  const query = useQuery<Order>('/api/orders/' + id);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const order = query.data;
  async function update(action: string) {
    setBusy(true); setError('');
    try { query.setData(await api<Order>('/api/orders/' + id + '/' + action, { method: 'POST' })); }
    catch (reason) { setError(errorText(reason)); }
    finally { setBusy(false); }
  }
  return <main className="wrap page"><div className="form-shell"><Link className="back" to="/dashboard">Dashboard</Link>
    <p className="eyebrow">SIMULATED CHECKOUT</p><h1>Order details</h1>
    <Result query={query}>{order && <><h2>{order.title}</h2><Status status={order.status} />
      <p className="big-price">{money(order.amount, order.currency)}</p><p>No money is collected and no card details are required.</p>
      <p><Link to={'/auctions/' + order.auctionId}>View auction result</Link></p>{error && <Problem message={error} />}
      {order.status === 'PENDING' && <div className="actions"><button className="button dark" disabled={busy} onClick={() => update('simulate-payment')}>Simulate payment</button>
        <button className="button outline" disabled={busy} onClick={() => update('cancel')}>Cancel order</button></div>}</>}</Result>
  </div></main>;
}
function AuctionEntry({ auction, secondary }: { auction: Auction; secondary: ReactNode }) {
  return <li className="entry"><Link to={'/auctions/' + auction.id}>{title(auction.vehicle)}</Link><span>{secondary}</span><Status status={auction.status} /></li>;
}
function EntryList({ children, empty }: { children: ReactNode[]; empty: string }) {
  return children.length ? <ul className="entries">{children}</ul> : <Empty>{empty}</Empty>;
}
export function Result({ query, children }: { query: { data: unknown; loading: boolean; error: string; reload: () => void }; children: ReactNode }) {
  if (query.error) return <Problem message={query.error} retry={query.reload} />;
  if (query.loading && !query.data) return <Loading />;
  return children;
}
