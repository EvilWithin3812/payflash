import { useMutation } from '@tanstack/react-query'
import { useState, type FormEvent, type ReactNode } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { api, errorMessage } from '../api/client'
import { useDemo } from '../api/queries'
import type { TokenResponse } from '../api/types'
import { signIn, useSession } from '../auth/session'
import { Alert, Button, Field } from '../components/ui'
import { LegalLinks } from './Legal'

export function AuthShell({ title, children, footer }: { title: string; children: ReactNode; footer: ReactNode }) {
  return (
    <div className="flex min-h-screen items-center justify-center bg-gradient-to-br from-brand-50 via-white to-slate-100 px-4">
      <div className="w-full max-w-sm">
        <div className="mb-8 text-center">
          <img src="/favicon.svg" alt="" className="mx-auto h-12 w-12" />
          <h1 className="mt-4 text-2xl font-bold tracking-tight">{title}</h1>
          <p className="mt-1 text-sm text-slate-500">UPI-style wallet and flash sales</p>
        </div>
        <div className="rounded-2xl bg-white p-6 shadow-sm ring-1 ring-slate-200">{children}</div>
        <p className="mt-6 text-center text-sm text-slate-600">{footer}</p>
        <LegalLinks className="mt-6 justify-center" />
      </div>
    </div>
  )
}

function useAfterLogin() {
  const navigate = useNavigate()
  const from = (useLocation().state as { from?: string } | null)?.from ?? '/'
  return (tokens: TokenResponse) => {
    signIn(tokens)
    navigate(from, { replace: true })
  }
}

export function Login() {
  const session = useSession()
  const done = useAfterLogin()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const demo = useDemo()
  const login = useMutation({
    mutationFn: async (creds?: { email: string; password: string }) =>
      (await api.post<TokenResponse>('/auth/login', creds ?? { email, password })).data,
    onSuccess: done,
  })

  if (session) return <Navigate to="/" replace />
  const submit = (e: FormEvent) => {
    e.preventDefault()
    login.mutate()
  }

  return (
    <AuthShell title="Log in to PayFlash" footer={<>New here? <Link className="font-semibold text-brand-600" to="/signup">Create an account</Link></>}>
      <form onSubmit={submit} className="space-y-4">
        <Field label="Email" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        <Field label="Password" type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
        <div className="-mt-2 text-right text-sm">
          <Link className="font-medium text-brand-600" to="/forgot-password">Forgot password?</Link>
        </div>
        {login.isError && <Alert>{errorMessage(login.error)}</Alert>}
        <Button type="submit" loading={login.isPending} className="w-full">Log in</Button>
      </form>
      {demo.data?.enabled && demo.data.accounts.length > 0 && (
        <div className="mt-6 border-t border-slate-200 pt-5">
          <p className="text-sm font-semibold text-slate-900">Just looking? Try it in one click</p>
          <p className="mt-1 text-xs text-slate-500">
            Shared demo accounts with play money. Nothing here is real money.
          </p>
          <div className="mt-3 grid gap-2">
            {demo.data.accounts.map((a) => (
              <Button key={a.email} type="button" variant="secondary" className="w-full" disabled={login.isPending}
                onClick={() => login.mutate({ email: a.email, password: a.password })}>
                Continue as {a.fullName}
              </Button>
            ))}
          </div>
          <p className="mt-2 text-xs text-slate-500">
            Log in as both in two browsers and send money between them, or race each other in the live flash sale.
          </p>
        </div>
      )}
    </AuthShell>
  )
}

export function Signup() {
  const session = useSession()
  const done = useAfterLogin()
  const [form, setForm] = useState({ fullName: '', email: '', password: '' })
  const [acceptTerms, setAcceptTerms] = useState(false)
  const signup = useMutation({
    mutationFn: async () => (await api.post<TokenResponse>('/auth/signup', { ...form, acceptTerms })).data,
    onSuccess: done,
  })

  if (session) return <Navigate to="/" replace />
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [k]: e.target.value })
  const submit = (e: FormEvent) => {
    e.preventDefault()
    signup.mutate()
  }

  return (
    <AuthShell title="Create your wallet" footer={<>Already have an account? <Link className="font-semibold text-brand-600" to="/login">Log in</Link></>}>
      <form onSubmit={submit} className="space-y-4">
        <Field label="Full name" autoComplete="name" required maxLength={80} value={form.fullName} onChange={set('fullName')} />
        <Field label="Email" type="email" autoComplete="email" required value={form.email} onChange={set('email')} />
        <Field label="Password" type="password" autoComplete="new-password" required minLength={8} hint="At least 8 characters"
          value={form.password} onChange={set('password')} />
        <label className="flex items-start gap-2 text-sm text-slate-600">
          <input type="checkbox" required checked={acceptTerms} onChange={(e) => setAcceptTerms(e.target.checked)}
            className="mt-0.5 h-4 w-4 rounded border-slate-300 text-brand-600" />
          <span>
            I agree to the <Link className="font-medium text-brand-600 underline" to="/terms" target="_blank">Terms of Service</Link> and{' '}
            <Link className="font-medium text-brand-600 underline" to="/privacy" target="_blank">Privacy Policy</Link>
          </span>
        </label>
        {signup.isError && <Alert>{errorMessage(signup.error)}</Alert>}
        <Button type="submit" loading={signup.isPending} className="w-full">Create account</Button>
      </form>
    </AuthShell>
  )
}
