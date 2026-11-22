import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { api, errorMessage, newIdempotencyKey } from '../api/client'
import { useRecipients, useWallet } from '../api/queries'
import type { Transaction } from '../api/types'
import { PinField } from '../components/PinField'
import { usePinRequired } from '../lib/pin'
import { Alert, Button, Card, Field, PageTitle } from '../components/ui'
import { money } from '../lib/format'

export function Send() {
  const queryClient = useQueryClient()
  const wallet = useWallet()
  const recipients = useRecipients()
  const [form, setForm] = useState({ recipientEmail: '', amount: '', note: '' })
  const [pin, setPin] = useState('')
  // One key per transfer attempt: a double-click or a retry after a timeout replays instead of paying twice.
  const [key, setKey] = useState(newIdempotencyKey)
  const needsPin = usePinRequired(Number(form.amount) || 0)

  const transfer = useMutation({
    mutationFn: async () =>
      (
        await api.post<Transaction>(
          '/wallet/transfer',
          {
            recipientEmail: form.recipientEmail.trim(),
            amount: Number(form.amount),
            note: form.note || null,
            pin: needsPin ? pin : null,
          },
          { headers: { 'Idempotency-Key': key } },
        )
      ).data,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['wallet'] })
      queryClient.invalidateQueries({ queryKey: ['transactions'] })
      queryClient.invalidateQueries({ queryKey: ['recipients'] })
    },
    // On an error the key is kept: a wrong PIN saved nothing, so retrying with the right PIN goes through,
    // and a timed-out request that did succeed replays instead of paying twice.
  })

  const submit = (e: FormEvent) => {
    e.preventDefault()
    transfer.mutate()
  }
  const another = () => {
    transfer.reset()
    setForm({ recipientEmail: '', amount: '', note: '' })
    setPin('')
    setKey(newIdempotencyKey())
  }

  const chosen = form.recipientEmail.trim().toLowerCase()

  return (
    <div className="mx-auto max-w-lg">
      <PageTitle title="Send money" subtitle={wallet.data ? `Available: ${money(wallet.data.balance)}` : undefined} />
      <Card>
        {transfer.isSuccess ? (
          <div className="space-y-4 text-center">
            <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-emerald-100 text-2xl text-emerald-600">✓</div>
            <p className="text-lg font-semibold">Sent {money(transfer.data.amount)}</p>
            {transfer.data.counterparty && (
              <p className="text-sm text-slate-700">
                to <span className="font-medium">{transfer.data.counterparty.fullName}</span>
                {transfer.data.counterparty.email && <> ({transfer.data.counterparty.email})</>}
              </p>
            )}
            <p className="text-sm text-slate-500">Reference {transfer.data.reference}</p>
            <Button variant="secondary" onClick={another}>Send another</Button>
          </div>
        ) : (
          <form onSubmit={submit} className="space-y-4">
            {recipients.data && recipients.data.length > 0 && (
              <div>
                <p className="mb-1.5 text-sm font-medium text-slate-700">Recent</p>
                <div className="flex flex-wrap gap-2">
                  {recipients.data.map((r) => {
                    const selected = r.email?.toLowerCase() === chosen
                    return (
                      <button key={r.email} type="button" title={r.email ?? undefined} aria-pressed={selected}
                        onClick={() => setForm({ ...form, recipientEmail: r.email ?? '' })}
                        className={`flex items-center gap-2 rounded-full py-1 pl-1 pr-3 text-sm ring-1 transition ${
                          selected ? 'bg-brand-50 text-brand-700 ring-brand-300' : 'bg-white text-slate-700 ring-slate-200 hover:bg-slate-50'
                        }`}>
                        <span aria-hidden className="flex h-7 w-7 items-center justify-center rounded-full bg-slate-100 text-xs font-semibold text-slate-600">
                          {initials(r.fullName)}
                        </span>
                        {r.fullName}
                      </button>
                    )
                  })}
                </div>
              </div>
            )}
            <Field label="Recipient's email" type="email" required value={form.recipientEmail}
              onChange={(e) => setForm({ ...form, recipientEmail: e.target.value })} />
            <Field label="Amount (₹)" type="number" required min="0.01" step="0.01" inputMode="decimal" value={form.amount}
              onChange={(e) => setForm({ ...form, amount: e.target.value })} />
            <Field label="Note (optional)" maxLength={255} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} />
            {needsPin && <PinField value={pin} onChange={setPin} />}
            {transfer.isError && <Alert>{errorMessage(transfer.error)}</Alert>}
            <Button type="submit" loading={transfer.isPending} className="w-full">Send</Button>
          </form>
        )}
      </Card>
    </div>
  )
}

function initials(name: string): string {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]?.toUpperCase()).join('')
}
