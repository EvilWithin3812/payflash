import { useSales } from '../api/queries'
import { Alert, Empty, PageTitle, Spinner } from '../components/ui'
import { SaleCard } from './SaleCard'

export function Sales() {
  const { data, isError } = useSales()
  return (
    <div>
      <PageTitle title="Flash sales" subtitle="Stock updates live. One unit per person; pay before your hold runs out." />
      {isError && <Alert>Could not load sales.</Alert>}
      {!data && !isError && <Spinner />}
      {data?.length === 0 && <Empty>No upcoming or live sales. An admin can create one from the Admin page.</Empty>}
      <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
        {data?.map((s) => <SaleCard key={s.id} sale={s} />)}
      </div>
    </div>
  )
}
