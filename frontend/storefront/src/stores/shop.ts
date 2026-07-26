import { defineStore } from 'pinia'
import { api } from '../api'

export type Product = {
  productId: number
  name: string
  subtitle: string
  coverUrl?: string
  salesScene: string
  skuId: number
  skuName: string
  priceFen: number
  marketPriceFen: number
  inventory: number
}

export type CartItem = {
  id: number
  skuId: number
  productName: string
  skuName: string
  coverUrl?: string
  priceFen: number
  quantity: number
  selected: boolean
  inventory: number
}

export const useShopStore = defineStore('shop', {
  state: () => ({
    products: [] as Product[],
    cart: [] as CartItem[],
    loadingProducts: false,
    loadingCart: false
  }),
  getters: {
    selectedItems: state => state.cart.filter(item => item.selected),
    cartCount: state => state.cart.reduce((sum, item) => sum + item.quantity, 0)
  },
  actions: {
    async loadProducts() {
      this.loadingProducts = true
      try {
        this.products = await api<Product[]>('/catalog/products')
      } finally {
        this.loadingProducts = false
      }
    },
    async loadCart() {
      this.loadingCart = true
      try {
        this.cart = await api<CartItem[]>('/cart')
      } finally {
        this.loadingCart = false
      }
    },
    async setCart(skuId: number, quantity: number, selected = true) {
      await api(`/cart/items/${skuId}`, {
        method: 'PUT',
        body: JSON.stringify({ quantity, selected })
      })
      await this.loadCart()
    }
  }
})
