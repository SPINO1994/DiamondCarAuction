import { createRoot } from 'react-dom/client';
import { BrowserRouter, Route, Routes, useLocation } from 'react-router-dom';
import { useEffect } from 'react';
import { AuthProvider, Footer, Header, Require } from './ui';
import { Browse, Detail, Home } from './Market';
import { AuthForm, Dashboard, Notifications, OrderDetail, ProfilePage } from './Account';
import { Editor } from './Editor';
import './style.css';

function App() {
  const { pathname } = useLocation();
  useEffect(() => { window.scrollTo(0, 0); }, [pathname]);
  return <AuthProvider><a href="#content" className="skip">Skip to content</a><Header /><div id="content">
    <Routes>
      <Route path="/" element={<Home />} />
      <Route path="/browse" element={<Browse />} />
      <Route path="/auctions/:id" element={<Detail />} />
      <Route path="/login" element={<AuthForm />} />
      <Route path="/register" element={<AuthForm register />} />
      <Route path="/dashboard" element={<Require><Dashboard /></Require>} />
      <Route path="/profile" element={<Require><ProfilePage /></Require>} />
      <Route path="/notifications" element={<Require><Notifications /></Require>} />
      <Route path="/orders/:id" element={<Require role="BUYER"><OrderDetail /></Require>} />
      <Route path="/seller/new" element={<Require role="SELLER"><Editor /></Require>} />
      <Route path="/seller/edit/:id" element={<Require role="SELLER"><Editor /></Require>} />
      <Route path="*" element={<main className="wrap page"><h1>Page unavailable</h1><a href="/browse">Browse auctions</a></main>} />
    </Routes>
  </div><Footer /></AuthProvider>;
}
createRoot(document.getElementById('root')!).render(<BrowserRouter><App /></BrowserRouter>);
