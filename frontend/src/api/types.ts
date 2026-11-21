// Shapes of the PayFlash REST API (mirrors the Java DTOs).

export type Role = 'USER' | 'ADMIN'

export interface TokenResponse {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresInSeconds: number
}

export interface ApiErrorBody {
  timestamp: string
  status: number
  code: string
  message: string
  fieldErrors: Record<string, string>
}

export interface Wallet {
  walletId: number
  currency: string
  balance: number
  updatedAt: string
}

export type TransactionType = 'TOPUP' | 'TRANSFER' | 'PURCHASE'
export type Direction = 'DEBIT' | 'CREDIT'

export interface Transaction {
  reference: string
  type: TransactionType
  status: string
  direction: Direction
  amount: number
  counterpartyWalletId: number | null
  note: string | null
  createdAt: string
}

export interface Page<T> {
  content: T[]
  page: { size: number; number: number; totalElements: number; totalPages: number }
}

export type SalePhase = 'UPCOMING' | 'LIVE' | 'ENDED'

export interface Sale {
  id: number
  name: string
  description: string | null
  price: number
  totalUnits: number
  remainingUnits: number
  startsAt: string
  endsAt: string
  holdSeconds: number
  phase: SalePhase
}

export type OrderState = 'PROCESSING' | 'HELD' | 'PAID' | 'EXPIRED'

export interface SaleOrder {
  holdToken: string
  saleId: number
  unitNo: number
  status: OrderState
  price: number
  expiresAt: string | null
  transactionReference: string | null
  createdAt: string | null
}

export interface StockUpdate {
  saleId: number
  remainingUnits: number
  totalUnits: number
}

export interface PaymentOrder {
  orderId: string
  amount: number
  amountMinor: number
  currency: string
  status: 'CREATED' | 'PAID' | 'FAILED'
  keyId: string
  transactionReference: string | null
}
