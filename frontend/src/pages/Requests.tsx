import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { api, errorMessage } from '../api/client'
import { useRecipients, useRequests, useRequestSummary } from '../api/queries'
import type { PaymentRequest, SplitResult } from '../api/types'
import { PinField } from '../components/PinField'
import { usePinRequired } from '../lib/pin'
import { Alert, Badge, Button, Card, Empty, Field, PageTitle, Spinner } from '../components/ui'
import { dateTime, money } from '../lib/format'

type Tab = 'INCOMING' | 'OUTGOING'
type Form = 'none' | 'request' | 'split'

export function Requests() {
  const [tab, setTab] = useState<Tab>('INCOMING')
  const [form, setForm] = useState<Form>('none')
  const summary = useRequestSummary()

  return (
    <div className="mx-auto max-w-3xl">
      <PageTitle
        title="Requests"
        subtitle="Ask friends to pay you, split a bill, and pay what others asked you for"
        action={
          <div className="flex gap-2">
            <Button variant={form === 'request' ? 'primary' : 'secondary'} onClick={() => setForm(form === 'request' ? 'none' : 'request')}>
              Request money
            </Button>
            <Button variant={form === 'split' ? 'primary' : 'secondary'} onClick={() => setForm(form === 'split' ? 'none' : 'split')}>
              Split a bill
            </Button>
          </div>
        }
      />

      {form === 'request' && <RequestForm onDone={() => { setForm('none'); setTab('OUTGOING') }} />}
      {form === 'split' && <SplitForm onDone={() => setTab('OUTGOING')} />}

      <Card className="p-0">
        <div role="tablist" aria-label="Requests" className="flex border-b border-slate-200">
          {(['INCOMING', 'OUTGOING'] as Tab[]).map((t) => {
            const count = t === 'INCOMING' ? summary.data?.waitingForMe : summary.data?.waitingForOthers
            return (
              <button key={t} role="tab" aria-selected={tab === t} onClick={() => setTab(t)}
                className={`flex flex-1 items-center justify-center gap-2 px-4 py-3 text-sm font-semibold transition ${
                  tab === t ? 'border-b-2 border-brand-600 text-brand-700' : 'text-slate-500 hover:text-slate-800'
                }`}>
                {t === 'INCOMING' ? 'To pay' : 'Requested by me'}
                {count ? <Badge tone={t === 'INCOMING' ? 'amber' : 'slate'}>{count}</Badge> : null}
              </button>
            )
          })}
        </div>
        <div className="px-6 py-2">
          <RequestList direction={tab} />
        </div>
      </Card>
    </div>
  )
}

function RequestList({ direction }: { direction: Tab }) {
  const requests = useRequests(direction)
  if (requests.isPending) return <div className="py-10"><Spinner className="mx-auto h-6 w-6" /></div>
  if (requests.isError) return <div className="py-4"><Alert>{errorMessage(requests.error)}</Alert></div>
  if (requests.data.content.length === 0) {
    return (
      <Empty>
        {direction === 'INCOMING' ? 'Nobody has asked you for money.' : 'You haven’t requested money yet.'}
      </Empty>
    )
  }
  return (
    <ul className="divide-y divide-slate-100">
      {requests.data.content.map((r) => <RequestRow key={r.id} request={r} />)}
    </ul>
  )
}

const statusTone = { PENDING: 'amber', PAID: 'green', DECLINED: 'red', CANCELLED: 'slate' } as const
const statusText = { PENDING: 'Waiting', PAID: 'Paid', DECLINED: 'Declined', CANCELLED: 'Cancelled' } as const

function RequestRow({ request: r }: { request: PaymentRequest }) {
  const queryClient = useQueryClient()
  const [paying, setPaying] = useState(false)
  const [pin, setPin] = useState('')
  const needsPin = usePinRequired(r.amount)
  const incoming = r.direction === 'INCOMING'
  const who = r.otherParty

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['requests'] })
    queryClient.invalidateQueries({ queryKey: ['wallet'] })
    queryClient.invalidateQueries({ queryKey: ['transactions'] })
  }
  const pay = useMutation({
    mutationFn: () => api.post(`/requests/${r.id}/pay`, { pin: needsPin ? pin : null }),
    onSuccess: () => { setPaying(false); refresh() },
  })
  const decline = useMutation({ mutationFn: () => api.post(`/requests/${r.id}/decline`), onSuccess: refresh })
  const cancel = useMutation({ mutationFn: () => api.post(`/requests/${r.id}/cancel`), onSuccess: refresh })
  const error = pay.error ?? decline.error ?? cancel.error

  const submitPay = (e: FormEvent) => {
    e.preventDefault()
    pay.mutate()
  }

  return (
    <li className="py-4">
      <div className="flex flex-wrap items-start gap-x-4 gap-y-2">
        <div className="min-w-0 flex-1">
          <p className="text-sm font-medium text-slate-900">
            {incoming ? `${who?.fullName ?? 'Someone'} requested` : `You requested from ${who?.fullName ?? 'someone'}`}
          </p>
          {who?.email && <p className="truncate text-xs text-slate-500">{who.email}</p>}
          <p className="mt-1 text-xs text-slate-500">
            {dateTime(r.createdAt)}
            {r.note && <> · “{r.note}”</>}
            {r.splitGroup && <> · <Badge tone="brand">Split bill</Badge></>}
          </p>
        </div>
        <div className="text-right">
          <p className="tabular text-base font-semibold text-slate-900">{money(r.amount)}</p>
          <Badge tone={statusTone[r.status]}>{statusText[r.status]}</Badge>
        </div>
      </div>

      {r.status === 'PENDING' && (
        <div className="mt-3">
          {incoming && !paying && (
            <div className="flex gap-2">
              <Button onClick={() => (needsPin ? setPaying(true) : pay.mutate())} loading={pay.isPending}>
                Pay {money(r.amount)}
              </Button>
              <Button variant="secondary" onClick={() => decline.mutate()} loading={decline.isPending}>Decline</Button>
            </div>
          )}
          {incoming && paying && (
            <form onSubmit={submitPay} className="max-w-xs space-y-3">
              <PinField value={pin} onChange={setPin} />
              <div className="flex gap-2">
                <Button type="submit" loading={pay.isPending}>Pay {money(r.amount)}</Button>
                <Button type="button" variant="ghost" onClick={() => { setPaying(false); pay.reset() }}>Back</Button>
              </div>
            </form>
          )}
          {!incoming && (
            <Button variant="secondary" onClick={() => cancel.mutate()} loading={cancel.isPending}>Cancel request</Button>
          )}
        </div>
      )}
      {error && <div className="mt-3"><Alert>{errorMessage(error)}</Alert></div>}
    </li>
  )
}

/** Quick picks from people you've paid before. */
function RecentPicker({ onPick, picked }: { onPick: (email: string) => void; picked: string[] }) {
  const recipients = useRecipients()
  if (!recipients.data || recipients.data.length === 0) return null
  return (
    <div>
      <p className="mb-1.5 text-sm font-medium text-slate-700">Recent</p>
      <div className="flex flex-wrap gap-2">
        {recipients.data.map((r) => {
          const on = picked.includes((r.email ?? '').toLowerCase())
          return (
            <button key={r.email} type="button" title={r.email ?? undefined} aria-pressed={on} onClick={() => onPick(r.email ?? '')}
              className={`rounded-full px-3 py-1 text-sm ring-1 transition ${
                on ? 'bg-brand-50 text-brand-700 ring-brand-300' : 'bg-white text-slate-700 ring-slate-200 hover:bg-slate-50'
              }`}>
              {r.fullName}
            </button>
          )
        })}
      </div>
    </div>
  )
}

function RequestForm({ onDone }: { onDone: () => void }) {
  const queryClient = useQueryClient()
  const [values, setValues] = useState({ payerEmail: '', amount: '', note: '' })
  const create = useMutation({
    mutationFn: () => api.post('/requests', {
      payerEmail: values.payerEmail.trim(), amount: Number(values.amount), note: values.note || null,
    }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['requests'] })
      onDone()
    },
  })
  const submit = (e: FormEvent) => {
    e.preventDefault()
    create.mutate()
  }
  return (
    <Card className="mb-6">
      <h2 className="font-semibold text-slate-900">Request money</h2>
      <p className="mt-1 text-sm text-slate-500">They get an email and see it under Requests, where they can pay or decline.</p>
      <form onSubmit={submit} className="mt-4 space-y-4">
        <RecentPicker picked={[values.payerEmail.trim().toLowerCase()]} onPick={(email) => setValues({ ...values, payerEmail: email })} />
        <Field label="Ask who? (their email)" type="email" required value={values.payerEmail}
          onChange={(e) => setValues({ ...values, payerEmail: e.target.value })} />
        <Field label="Amount (₹)" type="number" required min="1" step="0.01" inputMode="decimal" value={values.amount}
          onChange={(e) => setValues({ ...values, amount: e.target.value })} />
        <Field label="What for? (optional)" maxLength={255} placeholder="Movie tickets" value={values.note}
          onChange={(e) => setValues({ ...values, note: e.target.value })} />
        {create.isError && <Alert>{errorMessage(create.error)}</Alert>}
        <Button type="submit" loading={create.isPending}>Send request</Button>
      </form>
    </Card>
  )
}

function SplitForm({ onDone }: { onDone: () => void }) {
  const queryClient = useQueryClient()
  const [total, setTotal] = useState('')
  const [note, setNote] = useState('')
  const [friends, setFriends] = useState<string[]>([''])
  const [includeMe, setIncludeMe] = useState(true)

  const emails = friends.map((f) => f.trim().toLowerCase()).filter(Boolean)
  const people = new Set(emails).size + (includeMe ? 1 : 0)
  const share = people > 0 && Number(total) > 0 ? Math.floor((Number(total) / people) * 100) / 100 : 0

  const split = useMutation({
    mutationFn: async () =>
      (await api.post<SplitResult>('/requests/split', {
        totalAmount: Number(total), note: note || null, friendEmails: emails, includeMe,
      })).data,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['requests'] })
      onDone()
    },
  })

  const toggleFriend = (email: string) => {
    const lower = email.toLowerCase()
    if (emails.includes(lower)) setFriends(friends.filter((f) => f.trim().toLowerCase() !== lower))
    else setFriends([...friends.filter((f) => f.trim() !== ''), email])
  }
  const submit = (e: FormEvent) => {
    e.preventDefault()
    split.mutate()
  }

  if (split.isSuccess) {
    const result = split.data
    return (
      <Card className="mb-6">
        <h2 className="font-semibold text-slate-900">Bill split</h2>
        <p className="mt-1 text-sm text-slate-600">
          {money(result.totalAmount)} split {result.requests.length + (includeMe ? 1 : 0)} ways.
          {includeMe && <> Your share is {money(result.myShare)}.</>}
        </p>
        <ul className="mt-3 space-y-1 text-sm">
          {result.requests.map((r) => (
            <li key={r.id} className="flex justify-between gap-4">
              <span>{r.otherParty?.fullName} <span className="text-slate-500">({r.otherParty?.email})</span></span>
              <span className="tabular font-medium">{money(r.amount)}</span>
            </li>
          ))}
        </ul>
        <Button variant="secondary" className="mt-4" onClick={() => { split.reset(); setTotal(''); setNote(''); setFriends(['']) }}>
          Split another bill
        </Button>
      </Card>
    )
  }

  return (
    <Card className="mb-6">
      <h2 className="font-semibold text-slate-900">Split a bill</h2>
      <p className="mt-1 text-sm text-slate-500">Everyone pays an equal share. Each friend gets a request for theirs.</p>
      <form onSubmit={submit} className="mt-4 space-y-4">
        <Field label="Total bill (₹)" type="number" required min="1" step="0.01" inputMode="decimal" value={total}
          onChange={(e) => setTotal(e.target.value)} />
        <Field label="What for? (optional)" maxLength={255} placeholder="Dinner at Toit" value={note}
          onChange={(e) => setNote(e.target.value)} />
        <RecentPicker picked={emails} onPick={toggleFriend} />
        <fieldset className="space-y-2">
          <legend className="mb-1.5 text-sm font-medium text-slate-700">Friends&rsquo; emails</legend>
          {friends.map((f, i) => (
            <div key={i} className="flex gap-2">
              <input type="email" required={i === 0} value={f} aria-label={`Friend ${i + 1} email`}
                onChange={(e) => setFriends(friends.map((x, j) => (j === i ? e.target.value : x)))}
                className="block w-full rounded-lg border-0 px-3 py-2.5 text-slate-900 ring-1 ring-slate-300 focus:ring-2 focus:ring-brand-600 focus:outline-none" />
              {friends.length > 1 && (
                <Button type="button" variant="ghost" aria-label={`Remove friend ${i + 1}`}
                  onClick={() => setFriends(friends.filter((_, j) => j !== i))}>✕</Button>
              )}
            </div>
          ))}
          {friends.length < 10 && (
            <button type="button" className="text-sm font-semibold text-brand-600" onClick={() => setFriends([...friends, ''])}>
              + Add another friend
            </button>
          )}
        </fieldset>
        <label className="flex items-center gap-2 text-sm text-slate-700">
          <input type="checkbox" checked={includeMe} onChange={(e) => setIncludeMe(e.target.checked)}
            className="h-4 w-4 rounded border-slate-300 text-brand-600" />
          Include me (I pay my own share)
        </label>
        {share > 0 && people > 1 && (
          <p className="rounded-lg bg-slate-50 px-4 py-3 text-sm text-slate-700">
            {people} people · about <span className="font-semibold">{money(share)}</span> each
          </p>
        )}
        {split.isError && <Alert>{errorMessage(split.error)}</Alert>}
        <Button type="submit" loading={split.isPending}>Send {emails.length || ''} request{emails.length === 1 ? '' : 's'}</Button>
      </form>
    </Card>
  )
}
