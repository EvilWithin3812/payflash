import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { api, errorMessage, newIdempotencyKey } from '../api/client'
import { useWallet } from '../api/queries'
import type { Transaction } from '../api/types'
import { Alert, Button, Card, Field, PageTitle } from '../components/ui'
import { money } from '../lib/format'

export function Send() {
  const queryClient = useQueryClient()
  const wallet = useWallet()
  const [form, setForm] = useState({ recipientEmail: '', amount: '', note: '' })
  // One key per transfer attempt: a double-click or a retry after a timeout replays instead of paying twice.
  const [key, setKey] = useState(newIdempotencyKey)

  const transfer = useMutation({
    mutationFn: async () =>
      (
        await api.post<Transaction>(
          '/wallet/transfer',
          { recipientEmail: form.recipientEmail.trim(), amount: Number(form.amount), note: form.note || null },
          { headers: { 'Idempotency-Key': key } },
        )
      ).data,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['wallet'] })
      queryClient.invalidateQueries({ queryKey: ['transactions'] })
    },
  })

  const submit = (e: FormEvent) => {
    e.preventDefault()
    transfer.mutate()
  }
  const another = () => {
    transfer.reset()
    setForm({ recipientEmail: '', amount: '', note: '' })
    setKey(newIdempotencyKey())
  }

  return (
    <div className="mx-auto max-w-lg">
      <PageTitle title="Send money" subtitle={wallet.data ? `Available: ${money(wallet.data.balance)}` : undefined} />
      <Card>
        {transfer.isSuccess ? (
          <div className="space-y-4 text-center">
            <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-emerald-100 text-2xl text-emerald-600">✓</div>
            <p className="text-lg font-semibold">Sent {money(transfer.data.amount)}</p>
            <p className="text-sm text-slate-500">Reference {transfer.data.reference}</p>
            <Button variant="secondary" onClick={another}>Send another</Button>
          </div>
        ) : (
          <form onSubmit={submit} className="space-y-4">
            <Field label="Recipient's email" type="email" required value={form.recipientEmail}
              onChange={(e) => setForm({ ...form, recipientEmail: e.target.value })} />
            <Field label="Amount (₹)" type="number" required min="0.01" step="0.01" inputMode="decimal" value={form.amount}
              onChange={(e) => setForm({ ...form, amount: e.target.value })} />
            <Field label="Note (optional)" maxLength={255} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} />
            {transfer.isError && <Alert>{errorMessage(transfer.error)}</Alert>}
            <Button type="submit" loading={transfer.isPending} className="w-full">Send</Button>
          </form>
        )}
      </Card>
    </div>
  )
}
