import { useState } from 'react'
import { useTransactions } from '../api/queries'
import { Alert, Button, Card, PageTitle, Spinner } from '../components/ui'
import { TransactionList } from './TransactionList'

export function Transactions() {
  const [page, setPage] = useState(0)
  const { data, isError, isFetching } = useTransactions(page)
  const totalPages = data?.page.totalPages ?? 0

  return (
    <div className="mx-auto max-w-3xl">
      <PageTitle title="Transaction history" subtitle={data ? `${data.page.totalElements} transactions` : undefined} />
      <Card>
        {isError && <Alert>Could not load your transactions.</Alert>}
        {data ? <TransactionList items={data.content} /> : <Spinner />}
        {totalPages > 1 && (
          <div className="mt-4 flex items-center justify-between border-t border-slate-100 pt-4">
            <Button variant="secondary" disabled={page === 0 || isFetching} onClick={() => setPage((p) => p - 1)}>
              ← Newer
            </Button>
            <span className="text-sm text-slate-500">Page {page + 1} of {totalPages}</span>
            <Button variant="secondary" disabled={page + 1 >= totalPages || isFetching} onClick={() => setPage((p) => p + 1)}>
              Older →
            </Button>
          </div>
        )}
      </Card>
    </div>
  )
}
