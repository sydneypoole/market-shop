export type Address = {
  recipientName?: string
  phone?: string
  province?: string
  city?: string
  district?: string
  detailAddress?: string
  postalCode?: string
  contactName?: string
  contactPhone?: string
  address?: string
}

export function parseAddress(value?: string): Address | undefined {
  if (!value) return undefined
  try {
    const parsed: unknown = JSON.parse(value)
    return parsed && typeof parsed === 'object' ? parsed as Address : undefined
  } catch {
    return { address: value }
  }
}

export function addressLines(value?: string) {
  const address = parseAddress(value)
  if (!address) return []
  const name = address.recipientName || address.contactName
  const phone = address.phone || address.contactPhone
  const location = [
    address.province,
    address.city,
    address.district,
    address.detailAddress || address.address
  ].filter(Boolean).join(' ')
  return [
    [name, phone].filter(Boolean).join(' · '),
    location,
    address.postalCode ? `邮编 ${address.postalCode}` : ''
  ].filter(Boolean)
}
