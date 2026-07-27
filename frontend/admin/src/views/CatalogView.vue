<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { adminApi, dateTime, money } from '../api'
import {
  catalogStatusLabel,
  catalogStatusOptions,
  enableStatusLabel,
  enableStatusOptions,
  salesSceneLabel,
  salesSceneOptions
} from '../localization'
import { can } from '../session'
import AssetPicker from '../components/AssetPicker.vue'

type Category = { id:number;parentId?:number;name:string;code:string;sortOrder:number;status:string }
type Product = {
  productId:number;categoryId:number;name:string;subtitle?:string;coverUrl?:string;descriptionHtml?:string
  salesScene:string;status:string;sortOrder:number;skuId:number;skuCode:string;skuName:string
  priceFen:number;marketPriceFen?:number;attributesJson:string;skuStatus:string
  availableQuantity:number;reservedQuantity:number
}
type Adjustment = {
  id:number;skuId:number;adminId:number;beforeQuantity:number;afterQuantity:number
  reason:string;requestId:string;createdAt:string
}

const categories = ref<Category[]>([])
const products = ref<Product[]>([])
const error = ref('')
const keyword = ref('')
const productOpen = ref(false)
const categoryOpen = ref(false)
const historyOpen = ref<Product>()
const history = ref<Adjustment[]>([])
const mode = ref<'new'|'edit'|'sku'>('new')
const editing = ref<Product>()
const editingCategory = ref<Category>()
const preview = ref(false)
const busy = ref(false)
const product = reactive({
  categoryId:1,name:'',subtitle:'',coverUrl:'',descriptionHtml:'',salesScene:'UPGRADE',
  status:'ON_SALE',sortOrder:0,skuId:null as number|null,skuCode:'',skuName:'默认规格',
  priceFen:0,marketPriceFen:null as number|null,attributesJson:'{}',skuStatus:'ON_SALE',initialInventory:0
})
const category = reactive({ parentId:null as number|null,name:'',code:'',sortOrder:0,status:'ACTIVE' })
const visibleProducts = computed(() => {
  const value = keyword.value.trim().toLowerCase()
  return value
    ? products.value.filter(row => `${row.name} ${row.skuCode} ${row.skuName}`.toLowerCase().includes(value))
    : products.value
})

async function load() {
  error.value = ''
  try {
    ;[categories.value, products.value] = await Promise.all([
      adminApi<Category[]>('/catalog/categories'),
      adminApi<Product[]>('/catalog/products')
    ])
  } catch (e) { error.value = (e as Error).message }
}

function resetProduct(row?: Product) {
  Object.assign(product, row ? {
    categoryId:row.categoryId,name:row.name,subtitle:row.subtitle || '',coverUrl:row.coverUrl || '',
    descriptionHtml:row.descriptionHtml || '',salesScene:row.salesScene,status:row.status,sortOrder:row.sortOrder,
    skuId:row.skuId,skuCode:row.skuCode,skuName:row.skuName,priceFen:row.priceFen,
    marketPriceFen:row.marketPriceFen ?? null,attributesJson:row.attributesJson,skuStatus:row.skuStatus,
    initialInventory:row.availableQuantity
  } : {
    categoryId:categories.value[0]?.id || 1,name:'',subtitle:'',coverUrl:'',descriptionHtml:'',
    salesScene:'UPGRADE',status:'ON_SALE',sortOrder:0,skuId:null,skuCode:`SKU-${Date.now()}`,
    skuName:'默认规格',priceFen:0,marketPriceFen:null,attributesJson:'{}',skuStatus:'ON_SALE',initialInventory:0
  })
}

function openProduct(row?: Product) {
  mode.value = row ? 'edit' : 'new'
  editing.value = row
  resetProduct(row)
  preview.value = false
  productOpen.value = true
}

function addSku(row: Product) {
  mode.value = 'sku'
  editing.value = row
  resetProduct(row)
  Object.assign(product, {
    skuId:null,skuCode:`SKU-${Date.now()}`,skuName:'新规格',priceFen:row.priceFen,
    marketPriceFen:row.marketPriceFen ?? null,attributesJson:'{}',skuStatus:'ON_SALE',initialInventory:0
  })
  productOpen.value = true
}

async function saveProduct() {
  error.value = ''
  try {
    JSON.parse(product.attributesJson)
  } catch {
    error.value = '规格属性必须是有效的结构化数据'
    return
  }
  busy.value = true
  try {
    const productId = mode.value === 'new' ? undefined : editing.value?.productId
    const saved = await adminApi<Product>(`/catalog/products${productId ? `/${productId}` : ''}`, {
      method:productId ? 'PUT' : 'POST',
      body:JSON.stringify(product)
    })
    if (mode.value === 'edit' && saved.availableQuantity !== product.initialInventory) {
      await adjustInventory(saved, product.initialInventory)
    }
    productOpen.value = false
    await load()
  } catch (e) { error.value = (e as Error).message }
  finally { busy.value = false }
}

async function adjustInventory(row: Product, target?: number) {
  const afterQuantity = target ?? Number(prompt('调整后的可售库存', String(row.availableQuantity)))
  if (!Number.isInteger(afterQuantity) || afterQuantity < 0) return
  const reason = prompt('库存调整原因') || ''
  if (!reason) return
  await adminApi(`/catalog/skus/${row.skuId}/inventory-adjustments`, {
    method:'POST',
    body:JSON.stringify({ afterQuantity, reason, requestId:crypto.randomUUID() })
  })
  if (target === undefined) await load()
}

async function openHistory(row: Product) {
  historyOpen.value = row
  history.value = await adminApi<Adjustment[]>(`/catalog/skus/${row.skuId}/inventory-adjustments`)
}

function openCategory(row?: Category) {
  editingCategory.value = row
  Object.assign(category, row || { parentId:null,name:'',code:'',sortOrder:0,status:'ACTIVE' })
  categoryOpen.value = true
}

async function saveCategory() {
  await adminApi(`/catalog/categories${editingCategory.value ? `/${editingCategory.value.id}` : ''}`, {
    method:editingCategory.value ? 'PUT' : 'POST',
    body:JSON.stringify(category)
  })
  categoryOpen.value = false
  await load()
}

async function disableCategory(row: Category) {
  if (!confirm('确定停用该分类吗？')) return
  await adminApi(`/catalog/categories/${row.id}`, { method:'DELETE' })
  await load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-title">
      <div><h1>商品、规格与库存</h1><p>支持多规格商品、共享素材库、图文预览和库存调整流水。</p></div>
      <div v-if="can('catalog:write')" class="head-actions"><button class="secondary" @click="openCategory()">新增分类</button><button class="primary" @click="openProduct()">新增商品</button></div>
    </div>
    <div class="toolbar"><input v-model="keyword" placeholder="搜索商品 / SKU" /><button class="secondary" @click="load">刷新</button></div>
    <p v-if="error" class="error">{{ error }}</p>
    <section class="category-strip">
      <button v-for="row in categories" :key="row.id" class="card" @click="can('catalog:write') && openCategory(row)">
        <b>{{ row.name }}</b><small>{{ row.code }} · {{ enableStatusLabel(row.status) }}</small>
        <i v-if="can('catalog:write')" @click.stop="disableCategory(row)">停用</i>
      </button>
    </section>
    <div class="card table-wrap">
      <table>
        <thead><tr><th>商品 / 规格</th><th>分类</th><th>场景</th><th>价格</th><th>库存</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="row in visibleProducts" :key="`${row.productId}-${row.skuId}`">
            <td><div class="product-cell"><img v-if="row.coverUrl" :src="row.coverUrl" :alt="row.name" /><span><b>{{ row.name }}</b><small>{{ row.skuCode }} · {{ row.skuName }}</small></span></div></td>
            <td>{{ categories.find(item => item.id === row.categoryId)?.name }}</td>
            <td>{{ salesSceneLabel(row.salesScene) }}</td><td>{{ money(row.priceFen) }}</td>
            <td>可售 {{ row.availableQuantity }} / 占用 {{ row.reservedQuantity }}</td>
            <td><span class="tag" :class="{green:row.status === 'ON_SALE' && row.skuStatus === 'ON_SALE'}">商品{{ catalogStatusLabel(row.status) }} / 规格{{ catalogStatusLabel(row.skuStatus) }}</span></td>
            <td class="actions">
              <button v-if="can('catalog:write')" class="secondary" @click="openProduct(row)">编辑</button>
              <button v-if="can('catalog:write')" class="secondary" @click="addSku(row)">加规格</button>
              <button v-if="can('catalog:write')" class="primary" @click="adjustInventory(row)">调库存</button>
              <button class="secondary" @click="openHistory(row)">流水</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="productOpen" class="modal-mask" @click.self="productOpen = false">
      <form class="modal product-modal card" @submit.prevent="saveProduct">
        <div class="modal-title"><div><h2>{{ mode === 'new' ? '新增商品' : mode === 'sku' ? '新增商品规格' : '编辑商品与规格' }}</h2><p>商品资料对同一商品的全部规格生效。</p></div><button type="button" class="secondary" @click="productOpen = false">关闭</button></div>
        <div class="grid">
          <div class="field"><label>分类</label><select v-model.number="product.categoryId"><option v-for="item in categories" :key="item.id" :value="item.id">{{ item.name }}</option></select></div>
          <div class="field"><label>销售场景</label><select v-model="product.salesScene"><option v-for="option in salesSceneOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></div>
          <div class="field"><label>商品名称</label><input v-model="product.name" required /></div>
          <div class="field"><label>副标题</label><input v-model="product.subtitle" /></div>
          <div class="field"><label>商品状态</label><select v-model="product.status"><option v-for="option in catalogStatusOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></div>
          <div class="field"><label>排序</label><input v-model.number="product.sortOrder" type="number" /></div>
        </div>
        <AssetPicker v-model="product.coverUrl" />
        <div class="selected-image"><label>当前封面地址</label><input v-model="product.coverUrl" placeholder="可选择素材或填写外部 HTTPS 图片地址" /></div>
        <h3>规格信息</h3>
        <div class="grid">
          <div class="field"><label>SKU 编码</label><input v-model="product.skuCode" required /></div>
          <div class="field"><label>规格名称</label><input v-model="product.skuName" required /></div>
          <div class="field"><label>售价（分）</label><input v-model.number="product.priceFen" min="0" type="number" required /></div>
          <div class="field"><label>划线价（分）</label><input v-model.number="product.marketPriceFen" min="0" type="number" /></div>
          <div class="field"><label>规格状态</label><select v-model="product.skuStatus"><option v-for="option in catalogStatusOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></div>
          <div class="field"><label>{{ mode === 'edit' ? '调整后库存' : '初始库存' }}</label><input v-model.number="product.initialInventory" min="0" type="number" /></div>
        </div>
        <div class="field wide-field"><label>规格属性（结构化数据）</label><textarea v-model="product.attributesJson" rows="3" required /></div>
        <div class="description-head"><h3>图文详情</h3><button type="button" class="secondary" @click="preview = !preview">{{ preview ? '继续编辑' : '安全预览' }}</button></div>
        <div v-if="!preview" class="field"><textarea v-model="product.descriptionHtml" rows="8" placeholder="支持网页代码；内容将在隔离预览区中展示" /></div>
        <iframe v-else class="preview" sandbox="" :srcdoc="product.descriptionHtml || '<p>暂无详情</p>'" title="商品详情预览"></iframe>
        <div class="modal-actions"><button type="button" class="secondary" @click="productOpen = false">取消</button><button class="primary" :disabled="busy">{{ busy ? '保存中…' : '保存' }}</button></div>
      </form>
    </div>

    <div v-if="categoryOpen" class="modal-mask" @click.self="categoryOpen = false">
      <form class="modal card" @submit.prevent="saveCategory">
        <h2>{{ editingCategory ? '编辑分类' : '新增分类' }}</h2>
        <div class="field"><label>名称</label><input v-model="category.name" required /></div>
        <div class="field"><label>编码</label><input v-model="category.code" required /></div>
        <div class="field"><label>上级分类</label><select v-model="category.parentId"><option :value="null">无</option><option v-for="item in categories.filter(item => item.id !== editingCategory?.id)" :key="item.id" :value="item.id">{{ item.name }}</option></select></div>
        <div class="field"><label>排序</label><input v-model.number="category.sortOrder" type="number" /></div>
        <div class="field"><label>状态</label><select v-model="category.status"><option v-for="option in enableStatusOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></div>
        <div class="modal-actions"><button type="button" class="secondary" @click="categoryOpen = false">取消</button><button class="primary">保存</button></div>
      </form>
    </div>

    <div v-if="historyOpen" class="modal-mask" @click.self="historyOpen = undefined">
      <section class="modal history-modal card">
        <div class="modal-title"><div><h2>库存调整流水</h2><p>{{ historyOpen.skuCode }} · {{ historyOpen.skuName }}</p></div><button class="secondary" @click="historyOpen = undefined">关闭</button></div>
        <div class="table-wrap"><table><thead><tr><th>时间</th><th>调整前</th><th>调整后</th><th>操作人</th><th>原因</th><th>请求号</th></tr></thead><tbody><tr v-for="row in history" :key="row.id"><td>{{ dateTime(row.createdAt) }}</td><td>{{ row.beforeQuantity }}</td><td><b>{{ row.afterQuantity }}</b></td><td>#{{ row.adminId }}</td><td>{{ row.reason }}</td><td><code>{{ row.requestId }}</code></td></tr><tr v-if="!history.length"><td colspan="6">暂无人工调整流水。</td></tr></tbody></table></div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.head-actions,.actions,.modal-title,.description-head{display:flex;gap:8px}.modal-title,.description-head{justify-content:space-between;align-items:flex-start}.modal-title h2,.modal-title p{margin:0}.modal-title p{color:var(--muted);margin-top:5px}
.category-strip{display:flex;gap:10px;overflow:auto;margin-bottom:14px}.category-strip button{min-width:180px;padding:14px;text-align:left;border:1px solid var(--line);background:white}.category-strip small{display:block;color:var(--muted);margin-top:5px}.category-strip i{display:block;color:#a33;margin-top:8px;font-size:11px}
.product-cell{display:flex;align-items:center;gap:9px}.product-cell img{width:46px;height:46px;object-fit:cover;border-radius:8px}.product-cell small{display:block;color:var(--muted);margin-top:4px}.product-modal{width:min(1040px,100%);max-height:94vh;overflow:auto}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-top:14px}.wide-field,.selected-image{margin-top:12px}.selected-image{display:grid;gap:6px}.selected-image label{font-size:12px;color:var(--muted);font-weight:700}.selected-image input{padding:10px;border:1px solid var(--line);border-radius:9px}
.description-head{align-items:center;margin-top:12px}.description-head h3{margin:0}.preview{width:100%;height:240px;border:1px solid var(--line);border-radius:10px;background:white}.history-modal{width:min(900px,100%);max-height:90vh;overflow:auto}code{font-size:11px}
@media(max-width:700px){.grid{grid-template-columns:1fr}.actions{flex-wrap:wrap}}
</style>
