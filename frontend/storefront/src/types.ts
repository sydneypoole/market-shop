export type OrderSummary = {
  id: number
  orderNo: string
  buyerUserId: number
  superiorUserId: number
  totalAmountFen: number
  status: string
  reason?: string
  createdAt: string
}

export type OrderItem = {
  skuId: number
  productName: string
  skuName: string
  coverUrl?: string
  salesScene: string
  unitPriceFen: number
  quantity: number
  subtotalFen: number
}

export type Shipment = {
  carrierCode: string
  carrierName: string
  trackingNo: string
  shippedAt: string
}

export type OrderDetail = {
  order: OrderSummary
  addressJson: string
  items: OrderItem[]
  shipment?: Shipment
  superiorConfirmedAt?: string
  adminReviewedAt?: string
  autoReceiveAt?: string
  completedAt?: string
}

export type Proof = {
  proofId: number
  orderId: number
  mediaType: string
  sizeBytes: number
  uploadedBy: number
  retainUntil: string
  createdAt: string
}

export type AfterSaleProof = {
  id: number
  afterSaleId: number
  proofType: string
  mediaType: string
  sizeBytes: number
  uploadedByUserId?: number
  retainUntil: string
  createdAt: string
}

export type SignedDownload = {
  signedUrl: string
  expiresAt: string
}

export type AfterSale = {
  id: number
  afterSaleNo: string
  orderId: number
  applicantUserId: number
  superiorUserId: number
  type: string
  status: string
  reason: string
  adminReason?: string
  returnAddressJson?: string
  returnCarrier?: string
  returnTrackingNo?: string
  createdAt: string
  completedAt?: string
}

export type RuleView = {
  id: number
  ruleCode: string
  version: number
  ruleType: string
  parametersJson: string
  status: string
  effectiveFrom: string
  effectiveTo?: string
}
