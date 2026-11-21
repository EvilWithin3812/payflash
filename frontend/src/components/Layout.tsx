import { useQueryClient } from '@tanstack/react-query'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useDemo } from '../api/queries'
import { signOut, useSession } from '../auth/session'
import { VerifyBanner } from '../pages/AccountPages'
import { LegalLinks } from '../pages/Legal'

const link = ({ isActive }: { isActive: boolean }) =>
  `rounded-lg px-3 py-2 text-sm font-medium transition-colors ${
    isActive ? 'bg-brand-50 text-brand-700' : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900'
  }`

export function Layout() {
  const session = useSession()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const demo = useDemo()

  const logout = () => {
    signOut()
    queryClient.clear()
    navigate('/login')
  }

  return (
    <div className="min-h-screen">
      <header className="sticky top-0 z-10 border-b border-slate-200 bg-white/90 backdrop-blur">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-6 gap-y-2 px-4 py-3">
          <NavLink to="/" className="flex items-center gap-2 font-bold text-slate-900">
            <img src="/favicon.svg" alt="" className="h-7 w-7" />
            PayFlash
          </NavLink>
          <nav className="flex flex-1 flex-wrap items-center gap-1" aria-label="Main">
            <NavLink to="/" end className={link}>Wallet</NavLink>
            <NavLink to="/send" className={link}>Send</NavLink>
            <NavLink to="/add-money" className={link}>Add money</NavLink>
            <NavLink to="/transactions" className={link}>History</NavLink>
            <NavLink to="/sales" className={link}>Flash sales</NavLink>
            <NavLink to="/orders" className={link}>My orders</NavLink>
            <NavLink to="/notifications" className={link}>Notifications</NavLink>
            {session?.role === 'ADMIN' && <NavLink to="/admin" className={link}>Admin</NavLink>}
          </nav>
          <div className="flex items-center gap-3 text-sm">
            <NavLink to="/account" className={link} title={session?.email}>Account</NavLink>
            <button onClick={logout} className="rounded-lg px-3 py-2 font-medium text-slate-600 hover:bg-slate-100">
              Log out
            </button>
          </div>
        </div>
      </header>
      {demo.data?.enabled && (
        <div className="bg-slate-900 px-4 py-1.5 text-center text-xs text-slate-200">
          Live demo: all money here is play money. &ldquo;Add money&rdquo; is free and no real payments are made.
        </div>
      )}
      <VerifyBanner />
      <main className="mx-auto max-w-6xl px-4 py-8">
        <Outlet />
      </main>
      <footer className="mx-auto max-w-6xl px-4 pb-8">
        <LegalLinks />
      </footer>
    </div>
  )
}
