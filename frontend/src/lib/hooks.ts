import { Client } from '@stomp/stompjs'
import { useEffect, useState } from 'react'
import type { StockUpdate } from '../api/types'

/** Re-renders every `intervalMs` and returns the current time (for countdowns). */
export function useNow(intervalMs = 1000): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), intervalMs)
    return () => clearInterval(id)
  }, [intervalMs])
  return now
}

/*
 * One shared STOMP connection for the whole page, opened on first use. Each sale card subscribes to
 * /topic/sales/{id}; the server pushes the units-left count at most twice a second.
 */
let client: Client | null = null
let connected = false
const listeners = new Map<number, Set<(u: StockUpdate) => void>>()
const subscriptions = new Map<number, { unsubscribe: () => void }>()

function wsUrl() {
  const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
  return `${proto}://${window.location.host}/ws`
}

function subscribeOnServer(saleId: number) {
  if (!client || !connected || subscriptions.has(saleId)) return
  const sub = client.subscribe(`/topic/sales/${saleId}`, (msg) => {
    const update = JSON.parse(msg.body) as StockUpdate
    listeners.get(saleId)?.forEach((fn) => fn(update))
  })
  subscriptions.set(saleId, sub)
}

function ensureClient() {
  if (client) return
  client = new Client({
    brokerURL: wsUrl(),
    reconnectDelay: 3000,
    heartbeatIncoming: 10_000,
    heartbeatOutgoing: 10_000,
    onConnect: () => {
      connected = true
      subscriptions.clear() // server-side subscriptions died with the old connection
      listeners.forEach((_, saleId) => subscribeOnServer(saleId))
    },
    onWebSocketClose: () => {
      connected = false
    },
  })
  client.activate()
}

/** Live units-left for a sale, starting from the value the REST call returned. */
export function useLiveStock(saleId: number | undefined, initial: number | undefined): number | undefined {
  const [remaining, setRemaining] = useState(initial)
  useEffect(() => setRemaining(initial), [initial])

  useEffect(() => {
    if (saleId === undefined) return
    ensureClient()
    const fn = (u: StockUpdate) => setRemaining(u.remainingUnits)
    if (!listeners.has(saleId)) listeners.set(saleId, new Set())
    listeners.get(saleId)!.add(fn)
    subscribeOnServer(saleId)
    return () => {
      const set = listeners.get(saleId)
      set?.delete(fn)
      if (set && set.size === 0) {
        listeners.delete(saleId)
        subscriptions.get(saleId)?.unsubscribe()
        subscriptions.delete(saleId)
      }
    }
  }, [saleId])

  return remaining
}
