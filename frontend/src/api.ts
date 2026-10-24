export type Profile = { id: string; name: string; email: string; role: 'BUYER' | 'SELLER' | 'ADMIN'; joinedAt: string };
export type Session = { accessToken: string; profile: Profile };
export type Vehicle = {
  make: string; model: string; trim: string; year: number; mileage: number; mileageUnit: string;
  transmission: string; fuel: string; bodyStyle: string; exterior: string; interior: string;
  location: string; description: string; condition: string; serviceHistory: string; images: string[];
};
export type Auction = {
  id: string; seller: string; vehicle: Vehicle; startingPrice: number; increment: number;
  startsAt: string; endsAt: string; status: string; price: number; currency: string;
  reserveMet: boolean; hasBids: boolean; version: number; serverTime: string;
};
export type Page = { items: Auction[]; page: number; total: number; size: number };
export type Managed = { auction: Auction; reserve: number | null; reason: string | null };
export type AuctionState = { auction: Auction; bids: { version: number; bidder: string; price: number; placedAt: string }[] };
export type Order = { id: string; auctionId: string; title: string; amount: number; currency: string; status: string; updatedAt: string };
export type Notice = { id: string; title: string; body: string; link: string; createdAt: string; readAt: string | null };

let session: Session | null = null;
let expired = false;
export const sessionExpired = () => expired;
let refreshing: Promise<Session | null> | null = null;
const listeners = new Set<(value: Session | null) => void>();

export function subscribe(listener: (value: Session | null) => void) {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}
export function setSession(value: Session | null) {
  if (value) expired = false;
  session = value;
  listeners.forEach(listener => listener(value));
}
export function updateProfile(profile: Profile) {
  if (session) setSession({ ...session, profile });
}
export function refreshSession() {
  if (!refreshing) {
    const refresh = async () => {
      const result = await fetch('/api/auth/refresh', { method: 'POST' });
      const value = result.ok ? await result.json() as Session : null;
      if (!value && session) expired = true;
      setSession(value);
      return value;
    };
    refreshing = (async () => navigator.locks ? await navigator.locks.request('diamond-refresh', refresh) : await refresh())()
      .finally(() => { refreshing = null; });
  }
  return refreshing;
}
export class ApiError extends Error {
  constructor(public status: number, message: string, public fields?: Record<string, string>) {
    super(message);
  }
}
export async function api<T>(path: string, options: RequestInit = {}, retry = true): Promise<T> {
  const token = session?.accessToken;
  const headers = new Headers(options.headers);
  if (token) headers.set('Authorization', 'Bearer ' + token);
  if (typeof options.body === 'string') headers.set('Content-Type', 'application/json');
  const result = await fetch(path, { ...options, headers });
  if (result.status === 401 && retry && token && !path.startsWith('/api/auth/')) {
    if (session?.accessToken !== token || await refreshSession()) return api<T>(path, options, false);
    throw new ApiError(401, 'Your session expired. Sign in again.');
  }
  if (!result.ok) {
    const error = await result.json().catch(() => ({}));
    const message = result.status === 429 ? 'Too many requests. Try again in a minute.' :
      error.message || (result.status === 403 ? 'You do not have access to this action.' : 'The request could not be completed.');
    throw new ApiError(result.status, message, error.fields);
  }
  if (result.status === 204) return undefined as T;
  return result.json() as Promise<T>;
}
export function json(body: unknown, method = 'POST'): RequestInit {
  return { method, body: JSON.stringify(body) };
}
export function money(value: number, currency: string) {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency, maximumFractionDigits: 2, minimumFractionDigits: 0 }).format(value);
}
export function dateTime(value: string) {
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
}
export function title(vehicle: Vehicle) { return [vehicle.year, vehicle.make, vehicle.model, vehicle.trim].filter(Boolean).join(' '); }
export const labels: Record<string, string> = {
  LIVE: 'Live auction', SCHEDULED: 'Scheduled', FINALIZING: 'Finalizing', SOLD: 'Sold', CANCELLED: 'Cancelled',
  NO_BIDS: 'No bids', RESERVE_NOT_MET: 'Reserve not met', REVIEW: 'In review', DRAFT: 'Draft', REJECTED: 'Rejected',
  PENDING: 'Pending', PAID: 'Paid',
};
export function errorText(error: unknown) {
  if (error instanceof ApiError && error.fields) return Object.values(error.fields).join(' ');
  return error instanceof Error ? error.message : 'Something went wrong.';
}

export async function getImage(path: string): Promise<Blob> {
  let response = await fetch(path, { headers: { Authorization: 'Bearer ' + (session?.accessToken ?? '') } });
  if (response.status === 401 && await refreshSession()) {
    response = await fetch(path, { headers: { Authorization: 'Bearer ' + (session?.accessToken ?? '') } });
  }
  if (!response.ok) throw new Error('Photo unavailable.');
  return response.blob();
}
