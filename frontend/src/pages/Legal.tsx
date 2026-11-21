import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { site } from '../legal/site'

function LegalPage({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="min-h-screen bg-slate-50">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-3xl items-center gap-2 px-4 py-3 font-bold text-slate-900">
          <Link to="/" className="flex items-center gap-2">
            <img src="/favicon.svg" alt="" className="h-7 w-7" />
            PayFlash
          </Link>
        </div>
      </header>
      <main className="mx-auto max-w-3xl px-4 py-10">
        <article className="space-y-4 rounded-2xl bg-white p-6 text-sm leading-relaxed text-slate-700 shadow-sm ring-1 ring-slate-200 sm:p-10 [&_h2]:mt-8 [&_h2]:text-base [&_h2]:font-semibold [&_h2]:text-slate-900 [&_li]:ml-5 [&_li]:list-disc">
          <h1 className="text-2xl font-bold tracking-tight text-slate-900">{title}</h1>
          <p className="text-xs text-slate-500">Last updated: {site.lastUpdated}</p>
          {children}
        </article>
        <LegalLinks className="mt-6 justify-center" />
      </main>
    </div>
  )
}

export function LegalLinks({ className = '' }: { className?: string }) {
  return (
    <nav aria-label="Legal" className={`flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-500 ${className}`}>
      <Link to="/terms" className="hover:text-slate-800">Terms</Link>
      <Link to="/privacy" className="hover:text-slate-800">Privacy</Link>
      <Link to="/refunds" className="hover:text-slate-800">Refunds &amp; cancellations</Link>
      <a href={`mailto:${site.supportEmail}`} className="hover:text-slate-800">Contact</a>
    </nav>
  )
}

function Contact() {
  return (
    <ul>
      <li>Operator: {site.operator}, {site.address}</li>
      <li>Support: {site.supportEmail}</li>
      <li>Grievance Officer: {site.grievanceOfficer}, {site.grievanceEmail}. We acknowledge complaints within 48 hours and resolve them within one month.</li>
    </ul>
  )
}

export function Terms() {
  return (
    <LegalPage title="Terms of Service">
      <p>
        These terms govern your use of PayFlash (the &ldquo;Service&rdquo;), operated by {site.operator}. By creating an
        account you agree to them. If you do not agree, do not use the Service.
      </p>

      <h2>1. Your account</h2>
      <ul>
        <li>You must be at least 18 years old and able to enter a binding contract under Indian law.</li>
        <li>Give accurate details and keep your email address up to date. You must verify your email before adding, sending or spending money.</li>
        <li>One account per person. You are responsible for everything done with your account; keep your password secret and tell us at once if you think someone else has used it.</li>
      </ul>

      <h2>2. Flash sales</h2>
      <ul>
        <li>Sale items, prices, quantities and times are shown on each sale page. Stock is limited and sold first come, first served.</li>
        <li>Reserving an item gives you a short hold. If you do not pay before the hold ends, the item is released to other buyers.</li>
        <li>Each person may reserve once per sale, including holds that expired unpaid.</li>
        <li>An order is confirmed only when payment succeeds and you see it as &ldquo;Paid&rdquo; in My orders.</li>
        <li>If a price or description is clearly wrong, we may cancel the order and refund you in full.</li>
      </ul>

      <h2>3. Payments</h2>
      <ul>
        <li>Online payments are processed by Razorpay. We never see or store your full card or UPI details.</li>
        <li>Prices are in Indian Rupees (INR) and include applicable taxes unless stated otherwise.</li>
        <li>Refunds and cancellations follow our <Link className="text-brand-600 underline" to="/refunds">Refund &amp; Cancellation Policy</Link>.</li>
      </ul>

      <h2>4. What you must not do</h2>
      <ul>
        <li>Use bots, scripts or multiple accounts to reserve or buy items, or to get around purchase limits.</li>
        <li>Interfere with the Service, probe it for weaknesses without our written permission, or overload it.</li>
        <li>Use the Service for anything unlawful, including fraud or money laundering.</li>
      </ul>
      <p>We may suspend or close accounts that break these rules and cancel orders placed through them.</p>

      <h2>5. Availability and liability</h2>
      <p>
        We work to keep the Service available and correct, but it is provided &ldquo;as is&rdquo;. To the extent the law
        allows, our total liability for any claim relating to an order is limited to the amount you paid for that order.
        Nothing in these terms limits rights you have under the Consumer Protection Act, 2019.
      </p>

      <h2>6. Ending your account</h2>
      <p>
        You can delete your account at any time from the Account page. We may close accounts that break these terms.
        What happens to your data is explained in our <Link className="text-brand-600 underline" to="/privacy">Privacy Policy</Link>.
      </p>

      <h2>7. Changes and governing law</h2>
      <p>
        We may update these terms; we will tell you by email or on the site before important changes take effect.
        These terms are governed by the laws of India, and the courts at {site.jurisdiction} have jurisdiction.
      </p>

      <h2>8. Contact and grievances</h2>
      <Contact />
    </LegalPage>
  )
}

export function Privacy() {
  return (
    <LegalPage title="Privacy Policy">
      <p>
        This policy explains what personal data {site.operator} (&ldquo;we&rdquo;) collects through PayFlash, why, and
        your rights under the Digital Personal Data Protection Act, 2023.
      </p>

      <h2>1. What we collect</h2>
      <ul>
        <li><b>Account details:</b> your name, email address and a one-way hash of your password (we never store the password itself).</li>
        <li><b>Transactions and orders:</b> amounts, dates, the other party of a transfer, sale orders and their status.</li>
        <li><b>Payment details from Razorpay:</b> payment and order ids and status. Card and UPI details stay with Razorpay.</li>
        <li><b>Technical data:</b> IP address and request logs, used for security (rate limiting, stopping abuse) and fixing faults.</li>
      </ul>

      <h2>2. Why we use it</h2>
      <ul>
        <li>To run your account, process payments and orders, and send you emails about them (receipts, verification, password resets).</li>
        <li>To keep the Service secure and prevent fraud and abuse.</li>
        <li>To meet legal obligations such as tax and accounting records.</li>
      </ul>
      <p>We do not sell your data, show ads, or use tracking cookies. Your login session is kept in your browser&rsquo;s local storage.</p>

      <h2>3. Who we share it with</h2>
      <ul>
        <li>Razorpay, to process payments.</li>
        <li>Our hosting and email providers, who process data only on our instructions.</li>
        <li>Authorities, when the law requires it.</li>
      </ul>

      <h2>4. How long we keep it</h2>
      <ul>
        <li>Account details: until you delete your account.</li>
        <li>Records of payments and orders: as long as tax and accounting laws require (typically up to 8 years), even after account deletion, with your name and email removed.</li>
        <li>Security logs: a short period, normally no more than 90 days.</li>
      </ul>

      <h2>5. Your rights</h2>
      <ul>
        <li>See a summary of the data we hold about you, and have it corrected or completed.</li>
        <li>Delete your account from the Account page. This removes your name, email and notifications; the payment records above are kept, anonymised.</li>
        <li>Withdraw consent, nominate someone to act for you, and complain to our Grievance Officer or the Data Protection Board of India.</li>
      </ul>

      <h2>6. Security</h2>
      <p>
        Data travels over HTTPS, passwords are hashed with bcrypt, links in emails are single-use and expire, and
        access to our systems is restricted. No system is perfectly secure; we will tell you and the authorities as
        the law requires if a breach affects your data.
      </p>

      <h2>7. Contact</h2>
      <Contact />
    </LegalPage>
  )
}

export function Refunds() {
  return (
    <LegalPage title="Refund & Cancellation Policy">
      <h2>1. Cancelling an order</h2>
      <ul>
        <li>An unpaid hold simply expires; you are not charged.</li>
        <li>You can cancel a paid order before it is dispatched by writing to {site.supportEmail} with your order id. You get a full refund.</li>
      </ul>

      <h2>2. Returns</h2>
      <ul>
        <li>If an item arrives damaged, defective or different from its description, tell us within 7 days of delivery, with photos.</li>
        <li>We will replace it or refund you in full, including any delivery charge. We may ask you to send the item back at our cost.</li>
      </ul>

      <h2>3. Refunds</h2>
      <ul>
        <li>Approved refunds go back to the original payment method (card, UPI or wallet) within 5&ndash;7 working days. Your bank may take a few more days to show it.</li>
        <li>If you were charged but the order did not go through, write to {site.supportEmail} and we will refund you in full.</li>
      </ul>

      <h2>4. Contact</h2>
      <Contact />
    </LegalPage>
  )
}
