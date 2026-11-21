import type { ReactNode } from 'react'
import { Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { useSession } from './auth/session'
import { Layout } from './components/Layout'
import { Account, ForgotPassword, ResetPassword, VerifyEmail } from './pages/AccountPages'
import { AddMoney } from './pages/AddMoney'
import { Admin } from './pages/Admin'
import { Dashboard } from './pages/Dashboard'
import { Login, Signup } from './pages/Auth'
import { Privacy, Refunds, Terms } from './pages/Legal'
import { Notifications } from './pages/Notifications'
import { Orders } from './pages/Orders'
import { SaleDetail } from './pages/SaleDetail'
import { Sales } from './pages/Sales'
import { Send } from './pages/Send'
import { Transactions } from './pages/Transactions'

function RequireAuth({ children }: { children: ReactNode }) {
  const session = useSession()
  const location = useLocation()
  if (!session) return <Navigate to="/login" replace state={{ from: location.pathname }} />
  return <>{children}</>
}

function RequireAdmin({ children }: { children: ReactNode }) {
  const session = useSession()
  return session?.role === 'ADMIN' ? <>{children}</> : <Navigate to="/" replace />
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/signup" element={<Signup />} />
      <Route path="/forgot-password" element={<ForgotPassword />} />
      <Route path="/reset-password" element={<ResetPassword />} />
      <Route path="/verify-email" element={<VerifyEmail />} />
      <Route path="/terms" element={<Terms />} />
      <Route path="/privacy" element={<Privacy />} />
      <Route path="/refunds" element={<Refunds />} />
      <Route
        element={
          <RequireAuth>
            <Layout />
          </RequireAuth>
        }
      >
        <Route index element={<Dashboard />} />
        <Route path="send" element={<Send />} />
        <Route path="add-money" element={<AddMoney />} />
        <Route path="transactions" element={<Transactions />} />
        <Route path="sales" element={<Sales />} />
        <Route path="sales/:saleId" element={<SaleDetail />} />
        <Route path="orders" element={<Orders />} />
        <Route path="notifications" element={<Notifications />} />
        <Route path="account" element={<Account />} />
        <Route path="admin" element={<RequireAdmin><Admin /></RequireAdmin>} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
