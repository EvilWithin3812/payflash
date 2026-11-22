import { useQuery } from '@tanstack/react-query'
import { api } from './client'
import type { Counterparty, Me, Page, PaymentRequest, RequestSummary, Sale, SaleOrder, Transaction, Wallet } from './types'

export const keys = {
  wallet: ['wallet'] as const,
  transactions: (page: number, size: number) => ['transactions', page, size] as const,
  sales: ['sales'] as const,
  sale: (id: number) => ['sales', id] as const,
  orders: ['orders'] as const,
  order: (token: string) => ['orders', token] as const,
  me: ['me'] as const,
  requests: (direction: 'INCOMING' | 'OUTGOING') => ['requests', direction] as const,
  requestSummary: ['requests', 'summary'] as const,
  recipients: ['recipients'] as const,
}

export const useMe = () =>
  useQuery({ queryKey: keys.me, queryFn: async () => (await api.get<Me>('/users/me')).data })

export const useRequests = (direction: 'INCOMING' | 'OUTGOING') =>
  useQuery({
    queryKey: keys.requests(direction),
    queryFn: async () =>
      (await api.get<Page<PaymentRequest>>('/requests', { params: { direction, size: 50 } })).data,
    refetchInterval: 20_000,
  })

/** Drives the badge on the "Requests" menu item. */
export const useRequestSummary = (enabled = true) =>
  useQuery({
    queryKey: keys.requestSummary,
    queryFn: async () => (await api.get<RequestSummary>('/requests/summary')).data,
    refetchInterval: 30_000,
    enabled,
  })

/** People I sent money to recently, newest first. */
export const useRecipients = () =>
  useQuery({ queryKey: keys.recipients, queryFn: async () => (await api.get<Counterparty[]>('/wallet/recipients')).data })

export const useWallet = () =>
  useQuery({ queryKey: keys.wallet, queryFn: async () => (await api.get<Wallet>('/wallet')).data })

export const useTransactions = (page: number, size = 20) =>
  useQuery({
    queryKey: keys.transactions(page, size),
    queryFn: async () => (await api.get<Page<Transaction>>('/wallet/transactions', { params: { page, size } })).data,
    placeholderData: (prev) => prev,
  })

export const useSales = () =>
  useQuery({ queryKey: keys.sales, queryFn: async () => (await api.get<Sale[]>('/sales')).data, refetchInterval: 15_000 })

export const useSale = (id: number) =>
  useQuery({ queryKey: keys.sale(id), queryFn: async () => (await api.get<Sale>(`/sales/${id}`)).data })

export const useOrders = () =>
  useQuery({ queryKey: keys.orders, queryFn: async () => (await api.get<SaleOrder[]>('/sales/orders')).data })

export interface DemoInfo {
  enabled: boolean
  accounts: { email: string; password: string; fullName: string; pin: string }[]
}

/** Shared demo logins; only filled when the server runs with DEMO_MODE=true. */
export const useDemo = () =>
  useQuery({
    queryKey: ['demo'],
    queryFn: async () => (await api.get<DemoInfo>('/demo')).data,
    staleTime: Infinity,
    retry: false,
  })
