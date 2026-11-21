import { useSyncExternalStore } from 'react'
import { currentSession, tokens, type Session } from '../api/client'
import type { TokenResponse } from '../api/types'

// `tokens.save/clear` fire "pf-auth"; "storage" covers logins/logouts in other tabs.
function subscribe(onChange: () => void) {
  window.addEventListener('pf-auth', onChange)
  window.addEventListener('storage', onChange)
  return () => {
    window.removeEventListener('pf-auth', onChange)
    window.removeEventListener('storage', onChange)
  }
}

let cachedKey: string | null = null
let cachedSession: Session | null = null

function snapshot(): Session | null {
  const key = tokens.access ?? tokens.refresh
  if (key !== cachedKey) {
    cachedKey = key
    cachedSession = currentSession()
  }
  return cachedSession
}

export function useSession(): Session | null {
  return useSyncExternalStore(subscribe, snapshot)
}

export const signIn = (t: TokenResponse) => tokens.save(t)
export const signOut = () => tokens.clear()
