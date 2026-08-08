const { fileSize } = require('./format')

function invalidCapabilities() {
  return {
    code: 'PROOF_CAPABILITIES_INVALID',
    message: '凭证上传规则暂不可用，请稍后重试',
    status: 0,
    statusCode: 0,
    data: null
  }
}

function resolveProofLimits(capabilities) {
  const caps = capabilities || {}
  const maxProofFiles = Number(caps.maxProofFiles)
  const maxProofSizeBytes = Number(caps.maxProofSizeBytes)
  if (
    !Number.isInteger(maxProofFiles) ||
    maxProofFiles < 1 ||
    !Number.isFinite(maxProofSizeBytes) ||
    maxProofSizeBytes < 1
  ) {
    throw invalidCapabilities()
  }
  return {
    maxProofFiles: maxProofFiles,
    maxProofSizeBytes: maxProofSizeBytes,
    maxProofSizeText: fileSize(maxProofSizeBytes)
  }
}

function aftersaleProofType(status) {
  if (status === 'AWAITING_RETURN' || status === 'RETURN_SHIPPED') {
    return 'RETURN'
  }
  if (status === 'PENDING_OFFLINE_REFUND' || status === 'PENDING_BUYER_REFUND_CONFIRMATION') {
    return 'REFUND'
  }
  return 'APPLICATION'
}

module.exports = {
  resolveProofLimits,
  aftersaleProofType
}
