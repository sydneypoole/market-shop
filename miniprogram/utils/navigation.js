const DEFAULT_NAVIGATION_METRICS = Object.freeze({
  statusBarHeight: 20,
  navigationBarHeight: 44,
  sideWidth: 96
})

function positiveNumber(value, fallback) {
  const number = Number(value)
  return Number.isFinite(number) && number > 0 ? number : fallback
}

function getNavigationMetrics(wxApi) {
  let windowInfo = {}
  let menuButton = {}

  try {
    if (wxApi && typeof wxApi.getWindowInfo === 'function') {
      windowInfo = wxApi.getWindowInfo() || {}
    }
  } catch (e) {
    windowInfo = {}
  }

  try {
    if (wxApi && typeof wxApi.getMenuButtonBoundingClientRect === 'function') {
      menuButton = wxApi.getMenuButtonBoundingClientRect() || {}
    }
  } catch (e) {
    menuButton = {}
  }

  const statusBarHeight = positiveNumber(
    windowInfo.statusBarHeight,
    DEFAULT_NAVIGATION_METRICS.statusBarHeight
  )
  const windowWidth = positiveNumber(windowInfo.windowWidth, 375)
  const menuTop = positiveNumber(menuButton.top, statusBarHeight + 4)
  const menuHeight = positiveNumber(menuButton.height, 32)
  const menuLeft = positiveNumber(menuButton.left, windowWidth - 87)
  const verticalGap = Math.max(0, menuTop - statusBarHeight)
  const navigationBarHeight = Math.max(44, menuHeight + verticalGap * 2)
  const capsuleReserve = Math.max(0, windowWidth - menuLeft) + 8
  const sideWidth = Math.max(DEFAULT_NAVIGATION_METRICS.sideWidth, capsuleReserve)

  return {
    statusBarHeight: Math.round(statusBarHeight),
    navigationBarHeight: Math.round(navigationBarHeight),
    sideWidth: Math.round(sideWidth)
  }
}

module.exports = {
  DEFAULT_NAVIGATION_METRICS,
  getNavigationMetrics
}
