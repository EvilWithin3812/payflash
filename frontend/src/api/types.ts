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

/** The other person in a transfer (null for top-ups and purchases). */
export interface Counterparty {
  fullName: string
  email: string | null
  deleted: boolean
}

export interface Transaction {
  reference: string
  type: TransactionType
  status: string
  direction: Direction
  amount: number
  counterpartyWalletId: number | null
  counterparty: Counterparty | null
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

/** The signed-in user's profile (GET /users/me). */
export interface Me {
  id: number
  email: string
  fullName: string
  role: 'USER' | 'ADMIN'
  emailVerified: boolean
  createdAt: string
  pinSet: boolean
  /** Payments above this amount need the transaction PIN. */
  pinThreshold: number
}

export type RequestStatus = 'PENDING' | 'PAID' | 'DECLINED' | 'CANCELLED'

/** A payment request seen by one side: INCOMING = someone asked me to pay, OUTGOING = I asked someone. */
export interface PaymentRequest {
  id: number
  direction: 'INCOMING' | 'OUTGOING'
  status: RequestStatus
  amount: number
  note: string | null
  otherParty: Counterparty | null
  splitGroup: string | null
  transactionReference: string | null
  createdAt: string
  resolvedAt: string | null
}

export interface RequestSummary {
  waitingForMe: number
  waitingForOthers: number
}

export interface SplitResult {
  splitGroup: string
  totalAmount: number
  myShare: number
  requests: PaymentRequest[]
}
