package com.example.ui

import com.example.data.local.DuePaymentEntity
import com.example.data.local.ExpenseEntity
import com.example.data.local.InventoryMovementEntity
import com.example.data.local.PartyEntity
import com.example.data.local.PurchaseEntity
import com.example.data.local.PurchaseItemEntity
import com.example.data.local.SaleEntity
import com.example.data.local.SaleItemEntity
import com.example.data.local.VegetableEntity
import com.example.data.local.WastageEntity
import com.example.data.prefs.ShopPreferences
import com.example.domain.FinancialSummary

data class PosCartItem(
  val vegetable: VegetableEntity,
  val quantityUnits: Long,
  val sellingPricePerUnit: Double,
  val lineTotal: Double,
)

data class PurchaseDraftItem(
  val vegetable: VegetableEntity,
  val quantityUnits: Long,
  val unitCost: Double,
  val lineTotal: Double,
)

data class PartyBalanceSummary(
  val party: PartyEntity,
  val totalBilled: Double,
  val totalPaid: Double,
  val balanceDue: Double,
)

enum class ReportPeriod(val labelBn: String) {
  TODAY("আজ"),
  WEEK("৭ দিন"),
  MONTH("এই মাস"),
  ALL("সব সময়"),
  CUSTOM("তারিখ"),
}

data class ShopUiState(
  val isLoaded: Boolean = false,
  val preferences: ShopPreferences = ShopPreferences(),
  val isUnlocked: Boolean = false,
  val activeVegetables: List<VegetableEntity> = emptyList(),
  val allVegetables: List<VegetableEntity> = emptyList(),
  val lowStockVegetables: List<VegetableEntity> = emptyList(),
  val sales: List<SaleEntity> = emptyList(),
  val saleItemsBySaleId: Map<Long, List<SaleItemEntity>> = emptyMap(),
  val purchases: List<PurchaseEntity> = emptyList(),
  val purchaseItemsByPurchaseId: Map<Long, List<PurchaseItemEntity>> = emptyMap(),
  val wastages: List<WastageEntity> = emptyList(),
  val expenses: List<ExpenseEntity> = emptyList(),
  val duePayments: List<DuePaymentEntity> = emptyList(),
  val customerBalances: List<PartyBalanceSummary> = emptyList(),
  val supplierBalances: List<PartyBalanceSummary> = emptyList(),
  val movements: List<InventoryMovementEntity> = emptyList(),
  val todaySummary: FinancialSummary = FinancialSummary(),
  val posCart: List<PosCartItem> = emptyList(),
  val purchaseCart: List<PurchaseDraftItem> = emptyList(),
  val preselectedPosCustomerId: Long? = null,
  val isSaving: Boolean = false,
  val pinLockoutUntilMs: Long = 0L,
)
