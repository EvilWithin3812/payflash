import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api, errorMessage, refreshSession } from '../api/client'
import { signOut, useSession } from '../auth/session'
import { Alert, Button, Card, Field, PageTitle, Spinner } from '../components/ui'
import { AuthShell } from './Auth'

interface Me {
  id: number
  email: string
  fullName: string
  role: 'USER' | 'ADMIN'
  emailVerified: boolean
  createdAt: string
}

/**
 * Email links carry the token after '#' (never sent to any server, so it can't leak into logs).
 * Read it once, then wipe it from the address bar and browser history.
 */
function useLinkToken(): string | null {
  const [token] = useState(() => new URLSearchParams(window.location.hash.slice(1)).get('token'))
  useEffect(() => {
    if (window.location.hash) window.history.replaceState(null, '', window.location.pathname)
  }, [])
  return token
}

export function ForgotPassword() {
  const [email, setEmail] = useState('')
  const send = useMutation({ mutationFn: () => api.post('/auth/forgot-password', { email }) })
  const submit = (e: FormEvent) => {
    e.preventDefault()
    send.mutate()
  }
  return (
    <AuthShell title="Reset your password" footer={<Link className="font-semibold text-brand-600" to="/login">Back to log in</Link>}>
      {send.isSuccess ? (
        <Alert kind="success">
          If an account exists for <b>{email}</b>, we&rsquo;ve emailed a link to reset the password. It works for 30 minutes.
          Check your spam folder if you don&rsquo;t see it.
        </Alert>
      ) : (
        <form onSubmit={submit} className="space-y-4">
          <Field label="Email" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
          {send.isError && <Alert>{errorMessage(send.error)}</Alert>}
          <Button type="submit" loading={send.isPending} className="w-full">Email me a reset link</Button>
        </form>
      )}
    </AuthShell>
  )
}

export function ResetPassword() {
  const token = useLinkToken()
  const navigate = useNavigate()
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const reset = useMutation({
    mutationFn: () => api.post('/auth/reset-password', { token, newPassword: password }),
    onSuccess: () => signOut(), // every session was ended by the reset, including this browser's
  })
  const mismatch = confirm.length > 0 && password !== confirm
  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (!mismatch) reset.mutate()
  }
  return (
    <AuthShell title="Choose a new password" footer={<Link className="font-semibold text-brand-600" to="/login">Back to log in</Link>}>
      {!token ? (
        <Alert>This link is incomplete. Open the link from the email again, or <Link className="underline" to="/forgot-password">ask for a new one</Link>.</Alert>
      ) : reset.isSuccess ? (
        <div className="space-y-4">
          <Alert kind="success">Your password was changed and every device was logged out.</Alert>
          <Button className="w-full" onClick={() => navigate('/login')}>Log in</Button>
        </div>
      ) : (
        <form onSubmit={submit} className="space-y-4">
          <Field label="New password" type="password" autoComplete="new-password" required minLength={8} maxLength={72}
            hint="At least 8 characters" value={password} onChange={(e) => setPassword(e.target.value)} />
          <Field label="Repeat new password" type="password" autoComplete="new-password" required
            value={confirm} onChange={(e) => setConfirm(e.target.value)} />
          {mismatch && <Alert>The two passwords don&rsquo;t match.</Alert>}
          {reset.isError && <Alert>{errorMessage(reset.error)}</Alert>}
          <Button type="submit" loading={reset.isPending} className="w-full">Set new password</Button>
        </form>
      )}
    </AuthShell>
  )
}

export function VerifyEmail() {
  const token = useLinkToken()
  const session = useSession()
  const queryClient = useQueryClient()
  const started = useRef(false)
  const verify = useMutation({
    mutationFn: async () => {
      await api.post('/auth/verify-email', { token })
      if (session) await refreshSession() // pick up the "verified" flag in this browser's tokens
      await queryClient.invalidateQueries({ queryKey: ['me'] })
    },
  })
  useEffect(() => {
    // Strict Mode runs effects twice in development; the token works once, so send it once.
    if (token && !started.current) {
      started.current = true
      verify.mutate()
    }
  }, [token, verify])

  return (
    <AuthShell title="Email verification" footer={<Link className="font-semibold text-brand-600" to={session ? '/' : '/login'}>{session ? 'Go to my wallet' : 'Log in'}</Link>}>
      {!token && <Alert>This link is incomplete. Open the link from the email again.</Alert>}
      {verify.isPending && <div className="flex justify-center py-4"><Spinner className="h-6 w-6" /></div>}
      {verify.isSuccess && <Alert kind="success">Your email is verified. You can now add money, send money and buy in flash sales.</Alert>}
      {verify.isError && (
        <Alert>
          {errorMessage(verify.error)} {session && <>Ask for a new link on the <Link className="underline" to="/account">Account</Link> page.</>}
        </Alert>
      )}
    </AuthShell>
  )
}

/** Shown on every signed-in page until the email is verified. */
export function VerifyBanner() {
  const session = useSession()
  const resend = useMutation({ mutationFn: () => api.post('/auth/resend-verification') })
  if (!session || session.emailVerified) return null
  return (
    <div className="border-b border-amber-200 bg-amber-50">
      <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-4 gap-y-2 px-4 py-2.5 text-sm text-amber-900">
        <span>
          <b>Verify your email</b> to add money, send money and buy in flash sales. We sent a link to {session.email}.
        </span>
        {resend.isSuccess ? (
          <span className="font-medium">Sent. Check your inbox (and spam).</span>
        ) : (
          <button className="font-semibold underline disabled:opacity-50" disabled={resend.isPending} onClick={() => resend.mutate()}>
            Resend the email
          </button>
        )}
        <button className="font-semibold underline" onClick={() => void refreshSession()}>I&rsquo;ve verified it</button>
        {resend.isError && <span className="text-rose-700">{errorMessage(resend.error)}</span>}
      </div>
    </div>
  )
}

export function Account() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const me = useQuery({ queryKey: ['me'], queryFn: async () => (await api.get<Me>('/users/me')).data })
  const resend = useMutation({ mutationFn: () => api.post('/auth/resend-verification') })

  const leave = () => {
    signOut()
    queryClient.clear()
    navigate('/login')
  }
  const logoutAll = useMutation({ mutationFn: () => api.post('/users/me/logout-all'), onSuccess: leave })

  const [password, setPassword] = useState('')
  const [confirmText, setConfirmText] = useState('')
  const remove = useMutation({
    mutationFn: () => api.delete('/users/me', { data: { password } }),
    onSuccess: leave,
  })
  const submitDelete = (e: FormEvent) => {
    e.preventDefault()
    remove.mutate()
  }

  return (
    <div className="space-y-6">
      <PageTitle title="Account" subtitle="Your profile, sessions and data" />

      <Card className="p-6">
        <h2 className="font-semibold text-slate-900">Profile</h2>
        {me.isPending && <Spinner className="mt-4 h-5 w-5" />}
        {me.isError && <div className="mt-4"><Alert>{errorMessage(me.error)}</Alert></div>}
        {me.data && (
          <dl className="mt-4 grid gap-3 text-sm sm:grid-cols-[10rem_1fr]">
            <dt className="text-slate-500">Name</dt>
            <dd>{me.data.fullName}</dd>
            <dt className="text-slate-500">Email</dt>
            <dd className="flex flex-wrap items-center gap-2">
              {me.data.email}
              {me.data.emailVerified ? (
                <span className="rounded-full bg-emerald-50 px-2 py-0.5 text-xs font-medium text-emerald-700">Verified</span>
              ) : (
                <>
                  <span className="rounded-full bg-amber-50 px-2 py-0.5 text-xs font-medium text-amber-800">Not verified</span>
                  <button className="text-xs font-semibold text-brand-600 underline disabled:opacity-50" disabled={resend.isPending || resend.isSuccess}
                    onClick={() => resend.mutate()}>
                    {resend.isSuccess ? 'Link sent' : 'Send verification link'}
                  </button>
                </>
              )}
            </dd>
            <dt className="text-slate-500">Member since</dt>
            <dd>{new Date(me.data.createdAt).toLocaleDateString('en-IN', { dateStyle: 'long' })}</dd>
          </dl>
        )}
        <p className="mt-6 text-sm text-slate-500">
          Forgot your password, or want to change it? <Link className="font-medium text-brand-600" to="/forgot-password">Email me a reset link</Link>.
        </p>
      </Card>

      <Card className="p-6">
        <h2 className="font-semibold text-slate-900">Sessions</h2>
        <p className="mt-1 text-sm text-slate-500">Lost a phone or used a shared computer? Log out everywhere, including here.</p>
        {logoutAll.isError && <div className="mt-4"><Alert>{errorMessage(logoutAll.error)}</Alert></div>}
        <Button variant="secondary" className="mt-4" loading={logoutAll.isPending} onClick={() => logoutAll.mutate()}>
          Log out of all devices
        </Button>
      </Card>

      <Card className="p-6 ring-rose-200">
        <h2 className="font-semibold text-rose-700">Delete account</h2>
        <p className="mt-1 text-sm text-slate-600">
          This removes your name, email and notifications and ends every session. It can&rsquo;t be undone. Your wallet must
          be empty first. Records of past payments are kept, without your name, as the law requires
          (see the <Link className="underline" to="/privacy">Privacy Policy</Link>).
        </p>
        {me.data?.role === 'ADMIN' ? (
          <div className="mt-4"><Alert kind="info">Admin accounts can&rsquo;t be deleted from the app.</Alert></div>
        ) : (
          <form onSubmit={submitDelete} className="mt-4 max-w-sm space-y-4">
            <Field label="Your password" type="password" autoComplete="current-password" required
              value={password} onChange={(e) => setPassword(e.target.value)} />
            <Field label='Type "DELETE" to confirm' required value={confirmText} onChange={(e) => setConfirmText(e.target.value)} />
            {remove.isError && <Alert>{errorMessage(remove.error)}</Alert>}
            <Button type="submit" variant="danger" loading={remove.isPending} disabled={confirmText !== 'DELETE'}>
              Delete my account
            </Button>
          </form>
        )}
      </Card>
    </div>
  )
}
