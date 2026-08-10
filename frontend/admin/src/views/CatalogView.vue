<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { adminApi, adminErrorMessage, dateTime, money } from '../api'
import AssetPicker from '../components/AssetPicker.vue'
import RichTextEditor from '../components/RichTextEditor.vue'
import BaseDialog from '../components/admin/BaseDialog.vue'
import BusinessActionDialog from '../components/admin/BusinessActionDialog.vue'
import DetailDrawer from '../components/admin/DetailDrawer.vue'
import FilterBar from '../components/admin/FilterBar.vue'
import InlineAlert from '../components/admin/InlineAlert.vue'
import PageHeader from '../components/admin/PageHeader.vue'
import StatusTag from '../components/admin/StatusTag.vue'
import TableFrame from '../components/admin/TableFrame.vue'
import { catalogStatusLabel, catalogStatusOptions, enableStatusLabel, enableStatusOptions, salesSceneLabel, salesSceneOptions } from '../localization'
import { can } from '../session'
import { notifySuccess } from '../toast'

type Category = { id: number; parentId?: number; name: string; code: string; sortOrder: number; status: string }
type Product = {
  productId: number; categoryId: number; name: string; subtitle?: string; coverUrl?: string; descriptionHtml?: string
  salesScene: string; status: string; sortOrder: number; skuId: number; skuCode: string; skuName: string
  priceFen: number; marketPriceFen?: number; attributesJson: string; skuStatus: string
  availableQuantity: number; reservedQuantity: number
}
type Adjustment = {
  id: number; skuId: number; adminId: number; beforeQuantity: number; afterQuantity: number
  reason: string; requestId: string; createdAt: string
}
type ProductMode = 'new' | 'edit' | 'sku'

const route = useRoute()
const router = useRouter()
const categories = ref<Category[]>([])
const products = ref<Product[]>([])
const pageLoading = ref(true)
const listError = ref('')
const draftKeyword = ref('')
const appliedKeyword = ref('')

const productOpen = ref(false)
const productMode = ref<ProductMode>('new')
const editing = ref<Product>()
const preview = ref(false)
const productSubmitting = ref(false)
const productImageUploading = ref(false)
const productError = ref('')
const product = reactive({
  categoryId: 1, name: '', subtitle: '', coverUrl: '', descriptionHtml: '', salesScene: 'UPGRADE',
  status: 'ON_SALE', sortOrder: 0, skuId: null as number | null, skuCode: '', skuName: '默认规格',
  priceFen: 0, marketPriceFen: null as number | null, attributesJson: '{}', skuStatus: 'ON_SALE', initialInventory: 0
})

const categoryOpen = ref(false)
const editingCategory = ref<Category>()
const categorySubmitting = ref(false)
const categoryError = ref('')
const category = reactive({ parentId: null as number | null, name: '', code: '', sortOrder: 0, status: 'ACTIVE' })
const disablingCategory = ref<Category>()
const disableReason = ref('')
const disableError = ref('')
const disableSubmitting = ref(false)

const inventoryOpen = ref<Product>()
const inventory = reactive({ afterQuantity: 0, reason: '', requestId: '' })
const inventorySubmitting = ref(false)
const inventoryError = ref('')

const historyOpen = ref<Product>()
const history = ref<Adjustment[]>([])
const historyLoading = ref(false)
const historyError = ref('')
let historyRequestSequence = 0

const visibleProducts = computed(() => {
  const value = appliedKeyword.value.trim().toLowerCase()
  return value
    ? products.value.filter(row => `${row.name} ${row.skuCode} ${row.skuName}`.toLowerCase().includes(value))
    : products.value
})
const inventoryDifference = computed(() => inventoryOpen.value ? inventory.afterQuantity - inventoryOpen.value.availableQuantity : 0)

async function load() {
  pageLoading.value = true
  listError.value = ''
  try {
    ;[categories.value, products.value] = await Promise.all([
      adminApi<Category[]>('/catalog/categories'),
      adminApi<Product[]>('/catalog/products')
    ])
  } catch (cause) {
    listError.value = adminErrorMessage(cause)
  } finally { pageLoading.value = false }
}

function resetProduct(row?: Product) {
  Object.assign(product, row ? {
    categoryId: row.categoryId, name: row.name, subtitle: row.subtitle || '', coverUrl: row.coverUrl || '',
    descriptionHtml: row.descriptionHtml || '', salesScene: row.salesScene, status: row.status, sortOrder: row.sortOrder,
    skuId: row.skuId, skuCode: row.skuCode, skuName: row.skuName, priceFen: row.priceFen,
    marketPriceFen: row.marketPriceFen ?? null, attributesJson: row.attributesJson, skuStatus: row.skuStatus,
    initialInventory: row.availableQuantity
  } : {
    categoryId: categories.value[0]?.id || 1, name: '', subtitle: '', coverUrl: '', descriptionHtml: '',
    salesScene: 'UPGRADE', status: 'ON_SALE', sortOrder: 0, skuId: null, skuCode: `SKU-${Date.now()}`,
    skuName: '默认规格', priceFen: 0, marketPriceFen: null, attributesJson: '{}', skuStatus: 'ON_SALE', initialInventory: 0
  })
}

function openProduct(row?: Product) {
  productMode.value = row ? 'edit' : 'new'
  editing.value = row
  resetProduct(row)
  preview.value = false
  productImageUploading.value = false
  productError.value = ''
  productOpen.value = true
}

function addSku(row: Product) {
  productMode.value = 'sku'
  editing.value = row
  resetProduct(row)
  Object.assign(product, {
    skuId: null, skuCode: `SKU-${Date.now()}`, skuName: '新规格', priceFen: row.priceFen,
    marketPriceFen: row.marketPriceFen ?? null, attributesJson: '{}', skuStatus: 'ON_SALE', initialInventory: 0
  })
  preview.value = false
  productImageUploading.value = false
  productError.value = ''
  productOpen.value = true
}

async function saveProduct() {
  if (productSubmitting.value || productImageUploading.value) return
  productError.value = ''
  try { JSON.parse(product.attributesJson) }
  catch { productError.value = '规格属性必须是有效的结构化数据'; return }
  productSubmitting.value = true
  try {
    const productId = productMode.value === 'new' ? undefined : editing.value?.productId
    await adminApi<Product>(`/catalog/products${productId ? `/${productId}` : ''}`, {
      method: productId ? 'PUT' : 'POST', body: JSON.stringify(product)
    })
    productOpen.value = false
    notifySuccess(productMode.value === 'edit' ? '商品资料已保存' : productMode.value === 'sku' ? '商品规格已新增' : '商品已创建', productMode.value === 'edit' ? '库存未随资料保存改变，可使用独立库存调整操作。' : undefined)
    await load()
  } catch (cause) { productError.value = adminErrorMessage(cause) }
  finally { productSubmitting.value = false }
}

function openInventory(row: Product) {
  inventoryOpen.value = row
  inventory.afterQuantity = row.availableQuantity
  inventory.reason = ''
  inventory.requestId = crypto.randomUUID()
  inventoryError.value = ''
}

async function adjustInventory() {
  const row = inventoryOpen.value
  if (!row || inventorySubmitting.value) return
  inventorySubmitting.value = true
  inventoryError.value = ''
  try {
    await adminApi(`/catalog/skus/${row.skuId}/inventory-adjustments`, {
      method: 'POST', body: JSON.stringify({ afterQuantity: inventory.afterQuantity, reason: inventory.reason.trim(), requestId: inventory.requestId })
    })
    inventoryOpen.value = undefined
    notifySuccess('库存调整已完成', `${row.skuCode} 的库存已由 ${row.availableQuantity} 调整为 ${inventory.afterQuantity}。`)
    await load()
  } catch (cause) { inventoryError.value = adminErrorMessage(cause) }
  finally { inventorySubmitting.value = false }
}

async function openHistory(row: Product) {
  const request = ++historyRequestSequence
  historyOpen.value = row
  history.value = []
  historyError.value = ''
  historyLoading.value = true
  try {
    const result = await adminApi<Adjustment[]>(`/catalog/skus/${row.skuId}/inventory-adjustments`)
    if (request !== historyRequestSequence || historyOpen.value?.skuId !== row.skuId) return
    history.value = result
  } catch (cause) {
    if (request === historyRequestSequence) historyError.value = adminErrorMessage(cause)
  } finally {
    if (request === historyRequestSequence) historyLoading.value = false
  }
}

function closeHistory() {
  historyRequestSequence++
  historyOpen.value = undefined
  history.value = []
  historyError.value = ''
}

function openCategory(row?: Category) {
  editingCategory.value = row
  Object.assign(category, row || { parentId: null, name: '', code: '', sortOrder: 0, status: 'ACTIVE' })
  categoryError.value = ''
  categoryOpen.value = true
}

async function saveCategory() {
  if (categorySubmitting.value) return
  categorySubmitting.value = true
  categoryError.value = ''
  try {
    await adminApi(`/catalog/categories${editingCategory.value ? `/${editingCategory.value.id}` : ''}`, {
      method: editingCategory.value ? 'PUT' : 'POST', body: JSON.stringify(category)
    })
    categoryOpen.value = false
    notifySuccess(editingCategory.value ? '分类已更新' : '分类已创建')
    await load()
  } catch (cause) { categoryError.value = adminErrorMessage(cause) }
  finally { categorySubmitting.value = false }
}

function openDisableCategory(row: Category) { disablingCategory.value = row; disableReason.value = ''; disableError.value = '' }
async function disableCategory() {
  const row = disablingCategory.value
  if (!row || disableSubmitting.value) return
  disableSubmitting.value = true
  disableError.value = ''
  try {
    await adminApi(`/catalog/categories/${row.id}`, { method: 'DELETE', body: JSON.stringify({ reason: disableReason.value.trim() }) })
    disablingCategory.value = undefined
    notifySuccess('分类已停用')
    await load()
  } catch (cause) { disableError.value = adminErrorMessage(cause) }
  finally { disableSubmitting.value = false }
}

function readRouteState() {
  const keyword = typeof route.query.keyword === 'string' ? route.query.keyword : ''
  draftKeyword.value = keyword
  appliedKeyword.value = keyword
}

async function navigateKeyword() {
  const target = { path: '/catalog', query: appliedKeyword.value ? { keyword: appliedKeyword.value } : {} }
  if (router.resolve(target).fullPath !== route.fullPath) await router.push(target)
}

async function applyKeyword() {
  appliedKeyword.value = draftKeyword.value
  await navigateKeyword()
}

async function resetKeyword() {
  draftKeyword.value = ''
  appliedKeyword.value = ''
  await navigateKeyword()
}

watch(() => route.fullPath, () => { if (route.path === '/catalog') readRouteState() })
onMounted(() => { readRouteState(); void load() })
</script>

<template>
  <div>
    <PageHeader title="商品、规格与库存" description="商品资料与库存调整是两个独立、可解释、可重试的业务动作。"><template #actions><button v-if="can('catalog:write')" class="secondary" type="button" @click="openCategory()">新增分类</button><button v-if="can('catalog:write')" class="primary" type="button" @click="openProduct()">新增商品</button></template></PageHeader>
    <FilterBar :busy="pageLoading" :applied-summary="appliedKeyword ? [`关键词：${appliedKeyword}`] : ['全部商品与规格']" @apply="applyKeyword" @reset="resetKeyword"><label class="field"><span>商品或规格</span><input v-model="draftKeyword" placeholder="商品名称 / SKU 编码 / 规格" /></label></FilterBar>
    <section class="category-strip" aria-label="商品分类"><article v-for="row in categories" :key="row.id" class="card category-card"><button class="category-main" type="button" :disabled="!can('catalog:write')" @click="openCategory(row)"><b>{{ row.name }}</b><small>{{ row.code }} · {{ enableStatusLabel(row.status) }}</small></button><button v-if="can('catalog:write') && row.status === 'ACTIVE'" class="text-danger" type="button" @click="openDisableCategory(row)">停用</button></article></section>
    <TableFrame :loading="pageLoading" :error="listError" :empty="!visibleProducts.length" empty-title="暂无符合条件的商品" label="商品与库存列表" @retry="load"><table class="responsive-table"><thead><tr><th>商品 / 规格</th><th>分类</th><th>场景</th><th>价格</th><th>库存</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="row in visibleProducts" :key="`${row.productId}-${row.skuId}`"><td data-label="商品 / 规格"><div class="product-cell"><img v-if="row.coverUrl" :src="row.coverUrl" :alt="row.name" /><span><b>{{ row.name }}</b><small>{{ row.skuCode }} · {{ row.skuName }}</small></span></div></td><td data-label="分类">{{ categories.find(item => item.id === row.categoryId)?.name }}</td><td data-label="场景">{{ salesSceneLabel(row.salesScene) }}</td><td data-label="价格">{{ money(row.priceFen) }}</td><td data-label="库存"><b>可售 {{ row.availableQuantity }}</b><small>占用 {{ row.reservedQuantity }}</small></td><td data-label="状态"><StatusTag :tone="row.status === 'ON_SALE' && row.skuStatus === 'ON_SALE' ? 'success' : 'neutral'" :label="`商品${catalogStatusLabel(row.status)} / 规格${catalogStatusLabel(row.skuStatus)}`" /></td><td class="actions" data-label="操作"><button v-if="can('catalog:write')" class="primary" type="button" @click="openProduct(row)">编辑资料</button><button v-if="can('catalog:write')" class="secondary" type="button" @click="addSku(row)">新增商品规格</button><button v-if="can('catalog:write')" class="secondary" type="button" @click="openInventory(row)">调整库存</button><button class="secondary" type="button" @click="openHistory(row)">查看流水</button></td></tr></tbody></table></TableFrame>

    <BaseDialog v-model="productOpen" :title="productMode === 'new' ? '新增商品' : productMode === 'sku' ? '新增商品规格' : '编辑商品与规格'" description="资料保存不会触发库存调整；库存请使用列表中的独立操作。" width="min(1040px, calc(100vw - 32px))" :submitting="productSubmitting || productImageUploading"><form id="product-form" class="product-form" @submit.prevent="saveProduct"><div class="grid"><label class="field"><span>分类</span><select v-model.number="product.categoryId"><option v-for="item in categories" :key="item.id" :value="item.id">{{ item.name }}</option></select></label><label class="field"><span>销售场景</span><select v-model="product.salesScene"><option v-for="option in salesSceneOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></label><label class="field"><span>商品名称</span><input v-model="product.name" required /></label><label class="field"><span>副标题</span><input v-model="product.subtitle" /></label><label class="field"><span>商品状态</span><select v-model="product.status"><option v-for="option in catalogStatusOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></label><label class="field"><span>排序</span><input v-model.number="product.sortOrder" type="number" /></label></div><AssetPicker v-model="product.coverUrl" /><label class="field"><span>当前封面地址</span><input v-model="product.coverUrl" placeholder="选择素材或填写外部 HTTPS 图片地址" /></label><h3>规格信息</h3><div class="grid"><label class="field"><span>SKU 编码</span><input v-model="product.skuCode" required /></label><label class="field"><span>规格名称</span><input v-model="product.skuName" required /></label><label class="field"><span>售价（分）</span><input v-model.number="product.priceFen" min="0" type="number" required /></label><label class="field"><span>划线价（分）</span><input v-model.number="product.marketPriceFen" min="0" type="number" /></label><label class="field"><span>规格状态</span><select v-model="product.skuStatus"><option v-for="option in catalogStatusOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></label><label class="field"><span>{{ productMode === 'edit' ? '当前库存（只读）' : '初始库存' }}</span><input v-model.number="product.initialInventory" min="0" type="number" :readonly="productMode === 'edit'" /></label></div><label class="field"><span>规格属性（结构化数据）</span><textarea v-model="product.attributesJson" rows="3" required /></label><div class="description-head"><h3>图文详情</h3><button type="button" class="secondary" :disabled="productImageUploading" @click="preview = !preview">{{ preview ? '继续编辑' : '安全预览' }}</button></div><RichTextEditor v-if="!preview" v-model="product.descriptionHtml" placeholder="输入商品介绍、规格亮点和使用说明" @uploading-change="productImageUploading = $event" /><iframe v-else class="preview" sandbox="" :srcdoc="product.descriptionHtml || '<p>暂无详情</p>'" title="商品详情预览"></iframe><InlineAlert v-if="productError" title="商品资料未保存" :message="productError" /></form><template #footer="{ close }"><button class="secondary" type="button" autofocus :disabled="productSubmitting || productImageUploading" @click="close">取消</button><button class="primary" form="product-form" :disabled="productSubmitting || productImageUploading">{{ productImageUploading ? '图片上传中…' : productSubmitting ? '保存中…' : '保存商品资料' }}</button></template></BaseDialog>

    <BaseDialog v-model="categoryOpen" :title="editingCategory ? '编辑分类' : '新增分类'" :submitting="categorySubmitting"><form id="category-form" class="dialog-form" @submit.prevent="saveCategory"><label class="field"><span>名称</span><input v-model="category.name" required /></label><label class="field"><span>编码</span><input v-model="category.code" required /></label><label class="field"><span>上级分类</span><select v-model="category.parentId"><option :value="null">无</option><option v-for="item in categories.filter(item => item.id !== editingCategory?.id)" :key="item.id" :value="item.id">{{ item.name }}</option></select></label><label class="field"><span>排序</span><input v-model.number="category.sortOrder" type="number" /></label><label class="field"><span>状态</span><select v-model="category.status"><option v-for="option in enableStatusOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></label><InlineAlert v-if="categoryError" title="分类未保存" :message="categoryError" /></form><template #footer="{ close }"><button class="secondary" type="button" autofocus :disabled="categorySubmitting" @click="close">取消</button><button class="primary" form="category-form" :disabled="categorySubmitting">{{ categorySubmitting ? '保存中…' : '保存分类' }}</button></template></BaseDialog>

    <BaseDialog :model-value="Boolean(inventoryOpen)" title="调整可售库存" :description="inventoryOpen ? `${inventoryOpen.skuCode} · ${inventoryOpen.skuName}` : ''" :submitting="inventorySubmitting" @update:model-value="value => { if (!value) inventoryOpen = undefined }"><form id="inventory-form" class="dialog-form" @submit.prevent="adjustInventory"><div class="inventory-summary"><span><small>当前库存</small><b>{{ inventoryOpen?.availableQuantity }}</b></span><i aria-hidden="true">→</i><span><small>目标库存</small><b>{{ inventory.afterQuantity }}</b></span><span><small>调整量</small><b>{{ inventoryDifference > 0 ? '+' : '' }}{{ inventoryDifference }}</b></span></div><label class="field"><span>调整后可售库存</span><input v-model.number="inventory.afterQuantity" type="number" min="0" required /></label><label class="field"><span>调整原因</span><textarea v-model="inventory.reason" rows="3" maxlength="500" required /></label><p class="request-id">稳定请求号：<code>{{ inventory.requestId }}</code></p><InlineAlert v-if="inventoryError" title="库存调整未完成" :message="inventoryError" /></form><template #footer="{ close }"><button class="secondary" type="button" autofocus :disabled="inventorySubmitting" @click="close">取消</button><button class="primary" form="inventory-form" :disabled="inventorySubmitting || inventoryDifference === 0">{{ inventorySubmitting ? '调整中…' : '确认调整库存' }}</button></template></BaseDialog>

    <DetailDrawer :model-value="Boolean(historyOpen)" :title="historyOpen ? `${historyOpen.skuCode} · 库存流水` : '库存流水'" description="仅展示当前规格的服务端调整记录" width="min(900px, 100vw)" @update:model-value="value => { if (!value) closeHistory() }"><div v-if="historyLoading" class="history-state" role="status"><span class="state-spinner"></span>正在加载当前规格流水…</div><InlineAlert v-else-if="historyError" title="库存流水加载失败" :message="historyError" retryable @retry="historyOpen && openHistory(historyOpen)" /><TableFrame v-else :empty="!history.length" empty-title="暂无人工调整流水" label="库存调整流水"><table class="responsive-table"><thead><tr><th>时间</th><th>调整前</th><th>调整后</th><th>操作人</th><th>原因</th><th>请求号</th></tr></thead><tbody><tr v-for="row in history" :key="row.id"><td data-label="时间">{{ dateTime(row.createdAt) }}</td><td data-label="调整前">{{ row.beforeQuantity }}</td><td data-label="调整后"><b>{{ row.afterQuantity }}</b></td><td data-label="操作人">#{{ row.adminId }}</td><td data-label="原因">{{ row.reason }}</td><td data-label="请求号"><code>{{ row.requestId }}</code></td></tr></tbody></table></TableFrame></DetailDrawer>

    <BusinessActionDialog :model-value="Boolean(disablingCategory)" title="停用商品分类" :target="disablingCategory ? `${disablingCategory.name}（${disablingCategory.code}）` : '当前分类'" impact="分类停用后不会再作为可选运营入口；已有关联商品资料仍由服务端规则处理。" current-state="启用" next-state="停用" v-model:reason="disableReason" danger confirm-label="确认停用" :submitting="disableSubmitting" :error="disableError" @update:model-value="value => { if (!value) disablingCategory = undefined }" @submit="disableCategory" />
  </div>
</template>

<style scoped>
.category-strip{display:flex;gap:10px;overflow:auto;margin-bottom:14px}.category-card{display:flex;min-width:210px;padding:10px}.category-main{flex:1;padding:4px;text-align:left;border:0;background:transparent}.category-main b,.category-main small{display:block}.category-main small{color:var(--color-text-muted);margin-top:5px}.text-danger{align-self:flex-end;padding:4px;color:var(--color-danger);border:0;background:transparent;font-size:12px}.product-cell{display:flex;align-items:center;gap:9px}.product-cell img{width:46px;height:46px;object-fit:cover;border-radius:8px}.product-cell small,td>small{display:block;color:var(--color-text-muted);margin-top:4px}.actions{display:flex;gap:6px}.product-form,.dialog-form{display:grid;gap:13px}.grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}.product-form h3{margin-bottom:0;font-family:serif}.description-head{display:flex;justify-content:space-between;align-items:center}.description-head h3{margin:0}.preview{width:100%;height:260px;border:1px solid var(--color-border);border-radius:10px;background:#fff}.inventory-summary{display:grid;grid-template-columns:1fr auto 1fr 1fr;align-items:center;gap:10px;padding:14px;background:var(--color-surface-subtle);border-radius:10px}.inventory-summary small,.inventory-summary b{display:block}.inventory-summary small{color:var(--color-text-muted)}.inventory-summary b{margin-top:4px;font-size:20px}.inventory-summary i{font-style:normal;color:var(--color-text-muted)}.request-id{color:var(--color-text-muted);font-size:12px;word-break:break-all}.history-state{min-height:240px;display:flex;align-items:center;justify-content:center;gap:10px;color:var(--color-text-muted)}code{font-size:11px}
@media(max-width:700px){.grid{grid-template-columns:1fr}.actions{flex-wrap:wrap}.inventory-summary{grid-template-columns:1fr auto 1fr}.inventory-summary>span:last-child{grid-column:1/-1}}
</style>
