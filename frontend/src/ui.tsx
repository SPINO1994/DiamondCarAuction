import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { Link, Navigate, useLocation } from 'react-router-dom';
import { api, errorText, labels, money, refreshSession, sessionExpired, setSession, subscribe, title, type Auction, type Profile, type Session } from './api';

const Auth = createContext<{ session: Session | null; loading: boolean }>({ session: null, loading: true });
export const useAuth = () => useContext(Auth);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, update] = useState<Session | null>(null);
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    const unsubscribe = subscribe(update);
    refreshSession().catch(() => undefined).finally(() => setLoading(false));
    return unsubscribe;
  }, []);
  return <Auth.Provider value={{ session, loading }}>{children}</Auth.Provider>;
}

export function Require({ role, children }: { role?: Profile['role']; children: ReactNode }) {
  const { session, loading } = useAuth();
  const location = useLocation();
  if (loading) return <main className="wrap page"><Loading /></main>;
  if (!session) return <Navigate to="/login" state={{ from: location.pathname, expired: sessionExpired() }} replace />;
  if (role && session.profile.role !== role) return <main className="wrap page"><Problem message="This page is unavailable to your account." /></main>;
  return children;
}

export function useQuery<T>(path: string) {
  const [data, setData] = useState<T>();
  const [error, setError] = useState('');
  const [attempt, reload] = useState(0);
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    const controller = new AbortController();
    setError('');
    setLoading(true);
    api<T>(path, { signal: controller.signal }).then(value => {
      if (!controller.signal.aborted) setData(value);
    }).catch(reason => {
      if (!controller.signal.aborted) setError(errorText(reason));
    }).finally(() => {
      if (!controller.signal.aborted) setLoading(false);
    });
    return () => controller.abort();
  }, [path, attempt]);
  return { data, loading, error, reload: () => reload(value => value + 1), setData };
}

export function Mark() {
  return <span className="brand"><span className="brand-mark" aria-hidden="true">◇</span><span>DIAMOND<small>CAR AUCTION</small></span></span>;
}
export function Header() {
  const { session } = useAuth();
  const [error, setError] = useState('');
  async function logout() {
    try { await api('/api/auth/logout', { method: 'POST' }); setSession(null); setError(''); }
    catch (reason) { setError(errorText(reason)); }
  }
  return <header className="topbar"><div className="wrap nav">
    <Link to="/" aria-label="Diamond Car Auction home"><Mark /></Link>
    <nav aria-label="Main navigation"><Link to="/browse">Auctions</Link>
      {session && <Link to="/dashboard">Dashboard</Link>}
      {session && <Link to="/notifications">Notifications</Link>}
    </nav>
    <div className="account-links">{session ? <>
      <Link to="/profile">Profile</Link><button className="text-button" onClick={logout}>Sign out</button>
    </> : <><Link to="/login">Sign in</Link><Link className="button gold compact" to="/register">Register</Link></>}</div>
  </div>{error && <div className="wrap"><Problem message={error} /></div>}</header>;
}

export function Footer() {
  return <footer><div className="wrap footer-row"><Link to="/"><Mark /></Link>
    <span>Portfolio demo · Fictional listings and simulated checkout</span><Link to="/browse">Browse auctions</Link></div></footer>;
}
export function Loading() { return <p className="muted" role="status">Loading…</p>; }
export function Problem({ message, retry }: { message: string; retry?: () => void }) {
  return <div className="problem" role="alert">{message} {retry && <button className="text-button" onClick={retry}>Try again</button>}</div>;
}
export function Empty({ children }: { children: ReactNode }) { return <p className="empty">{children}</p>; }
export function Status({ status }: { status: string }) {
  return <span className={'status ' + status.toLowerCase()}>{labels[status] ?? status}</span>;
}
export function Photo({ src, alt, className = '' }: { src?: string; alt: string; className?: string }) {
  const [failed, setFailed] = useState('');
  return src && failed !== src ? <img src={src} alt={alt} className={className} onError={() => setFailed(src)} /> :
    <div className={'placeholder ' + className}>Photo unavailable</div>;
}
export function timeLeft(end: string, at = Date.now()) {
  const seconds = Math.max(0, Math.ceil((new Date(end).valueOf() - at) / 1000));
  if (!seconds) return 'Ending';
  if (seconds >= 86400) return Math.floor(seconds / 86400) + 'd ' + Math.floor(seconds % 86400 / 3600) + 'h';
  if (seconds >= 3600) return Math.floor(seconds / 3600) + 'h ' + Math.floor(seconds % 3600 / 60) + 'm';
  return Math.floor(seconds / 60) + ':' + String(seconds % 60).padStart(2, '0');
}
export function Card({ item }: { item: Auction }) {
  const vehicle = item.vehicle;
  return <Link to={'/auctions/' + item.id} className="card">
    <div className="card-photo"><Photo src={vehicle.images[0]} alt={title(vehicle)} /><Status status={item.status} /></div>
    <div className="card-body"><h3>{title(vehicle)}</h3>
      <p>{vehicle.mileage.toLocaleString()} {vehicle.mileageUnit} · {vehicle.transmission} · {vehicle.location}</p>
      <div className="card-bottom"><div><span>{item.status === 'SOLD' ? 'Sold for' : item.hasBids ? 'Current bid' : 'Opening bid'}</span>
        <strong>{money(item.price, item.currency)}</strong></div>
        {item.status === 'LIVE' && <span className="card-time">{timeLeft(item.endsAt, new Date(item.serverTime).valueOf())} left</span>}</div>
    </div>
  </Link>;
}
export function Cards({ items, empty = 'No auctions to show.' }: { items: Auction[]; empty?: string }) {
  return items.length ? <div className="cards">{items.map(item => <Card key={item.id} item={item} />)}</div> : <Empty>{empty}</Empty>;
}
