import { defineStore } from 'pinia'
import { api } from '../api'
import type { Category, Product, StorefrontContent, StorefrontTemplate } from '../types'
import { applyTemplateTheme, parseTemplate } from '../utils/template'

export type { Product } from '../types'

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
    contents: [] as StorefrontContent[],
    categories: [] as Category[],
    storefrontTemplate: undefined as StorefrontTemplate | undefined,
    cart: [] as CartItem[],
    loadingProducts: false,
    loadingStorefront: false,
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
    async loadStorefront() {
      this.loadingStorefront = true
      try {
        const [template, products, contents, categories] = await Promise.all([
          api<StorefrontTemplate>('/storefront/template'),
          api<Product[]>('/catalog/products'),
          api<StorefrontContent[]>('/content'),
          api<Category[]>('/catalog/categories')
        ])
        this.storefrontTemplate = template
        this.products = products
        this.contents = contents
        this.categories = categories
        applyTemplateTheme(parseTemplate(template))
      } finally {
        this.loadingStorefront = false
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
