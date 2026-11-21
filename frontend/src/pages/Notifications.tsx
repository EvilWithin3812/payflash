import { useQuery } from '@tanstack/react-query'
import { api, errorMessage } from '../api/client'
import { Alert, Card, Empty, PageTitle, Spinner } from '../components/ui'
import { dateTime } from '../lib/format'

interface NotificationItem {
  id: number
  eventReference: string
  channel: string
  subject: string
  body: string
  createdAt: string
}

/** Served by the separate notification-service (its own database), reached through the API gateway. */
export function Notifications() {
  const { data, isError, error } = useQuery({
    queryKey: ['notifications'],
    queryFn: async () => (await api.get<NotificationItem[]>('/notifications')).data,
    refetchInterval: 10_000,
  })

  return (
    <div className="mx-auto max-w-3xl">
      <PageTitle title="Notifications" subtitle="Emails sent to you about your wallet activity" />
      <Card>
        {isError && <Alert>{errorMessage(error)}</Alert>}
        {!data && !isError && <Spinner />}
        {data?.length === 0 && <Empty>No notifications yet. Add or send some money!</Empty>}
        <ul className="divide-y divide-slate-100">
          {data?.map((n) => (
            <li key={n.id} className="py-4">
              <div className="flex items-baseline justify-between gap-4">
                <p className="font-medium text-slate-900">{n.subject}</p>
                <span className="shrink-0 text-xs text-slate-500">{dateTime(n.createdAt)}</span>
              </div>
              <p className="mt-1 whitespace-pre-line text-sm text-slate-600">{n.body.trim()}</p>
            </li>
          ))}
        </ul>
      </Card>
    </div>
  )
}
