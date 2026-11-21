import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { api, errorCode, errorMessage, newIdempotencyKey } from '../api/client'
import type { PaymentOrder, Transaction } from '../api/types'
import { Alert, Button, Card, Field, PageTitle } from '../components/ui'
import { money } from '../lib/format'

interface RazorpayResponse {
  razorpay_order_id: string
  razorpay_payment_id: string
  razorpay_signature: string
}

declare global {
  interface Window {
    Razorpay?: new (options: Record<string, unknown>) => { open: () => void; on: (e: string, cb: (r: unknown) => void) => void }
  }
}

/** Loads Razorpay Checkout on demand, so pages that don't take payments never load third-party JS. */
function loadCheckout(): Promise<void> {
  if (window.Razorpay) return Promise.resolve()
  return new Promise((resolve, reject) => {
    const s = document.createElement('script')
    s.src = 'https://checkout.razorpay.com/v1/checkout.js'
    s.onload = () => resolve()
    s.onerror = () => reject(new Error('Could not load Razorpay Checkout'))
    document.body.appendChild(s)
  })
}

export function AddMoney() {
  const queryClient = useQueryClient()
  const [amount, setAmount] = useState('500')
  const [message, setMessage] = useState<{ kind: 'success' | 'error' | 'info'; text: string } | null>(null)
  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['wallet'] })
    queryClient.invalidateQueries({ queryKey: ['transactions'] })
  }

  const razorpay = useMutation({
    mutationFn: async () => {
      const { data: order } = await api.post<PaymentOrder>(
        '/payments/topup-orders',
        { amount: Number(amount) },
        { headers: { 'Idempotency-Key': newIdempotencyKey() } },
      )
      await loadCheckout()
      return order
    },
    onSuccess: (order) => {
      const checkout = new window.Razorpay!({
        key: order.keyId,
        order_id: order.orderId,
        amount: order.amountMinor,
        currency: order.currency,
        name: 'PayFlash',
        description: 'Wallet top-up',
        theme: { color: '#4f46e5' },
        handler: async (resp: RazorpayResponse) => {
          try {
            await api.post('/payments/verify', {
              razorpayOrderId: resp.razorpay_order_id,
              razorpayPaymentId: resp.razorpay_payment_id,
              razorpaySignature: resp.razorpay_signature,
            })
            setMessage({ kind: 'success', text: `${money(order.amount)} added to your wallet.` })
            refresh()
          } catch (e) {
            setMessage({ kind: 'error', text: errorMessage(e) })
          }
        },
      })
      checkout.on('payment.failed', () => setMessage({ kind: 'error', text: 'Payment failed. You were not charged.' }))
      checkout.open()
    },
    onError: (e) =>
      setMessage(
        errorCode(e) === 'PAYMENTS_NOT_CONFIGURED'
          ? { kind: 'info', text: 'Razorpay test keys are not configured on the server. Use the instant demo top-up below.' }
          : { kind: 'error', text: errorMessage(e) },
      ),
  })

  const demo = useMutation({
    mutationFn: async () =>
      (await api.post<Transaction>('/wallet/topup', { amount: Number(amount) }, { headers: { 'Idempotency-Key': newIdempotencyKey() } })).data,
    onSuccess: (t) => {
      setMessage({ kind: 'success', text: `${money(t.amount)} added to your wallet.` })
      refresh()
    },
    onError: (e) => setMessage({ kind: 'error', text: errorMessage(e) }),
  })

  const submit = (e: FormEvent) => {
    e.preventDefault()
    setMessage(null)
    razorpay.mutate()
  }

  return (
    <div className="mx-auto max-w-lg">
      <PageTitle title="Add money" subtitle="Top up your wallet with Razorpay (test mode)" />
      <Card>
        <form onSubmit={submit} className="space-y-4">
          <Field label="Amount (₹)" type="number" min="1" max="100000" step="0.01" required inputMode="decimal"
            value={amount} onChange={(e) => setAmount(e.target.value)} />
          <div className="flex flex-wrap gap-2">
            {[100, 500, 1000, 5000].map((v) => (
              <button key={v} type="button" onClick={() => setAmount(String(v))}
                className="rounded-full px-3 py-1 text-sm ring-1 ring-slate-300 hover:bg-slate-50">
                {money(v)}
              </button>
            ))}
          </div>
          {message && <Alert kind={message.kind}>{message.text}</Alert>}
          <Button type="submit" loading={razorpay.isPending} className="w-full">Pay with Razorpay</Button>
        </form>
        <div className="mt-6 border-t border-slate-100 pt-4">
          <p className="text-sm text-slate-500">
            No Razorpay keys? The demo top-up adds money instantly (a simulated gateway, disabled in production).
          </p>
          <Button variant="secondary" className="mt-3 w-full" loading={demo.isPending} onClick={() => { setMessage(null); demo.mutate() }}>
            Instant demo top-up
          </Button>
        </div>
      </Card>
    </div>
  )
}
