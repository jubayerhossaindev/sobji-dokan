package com.example

import com.example.data.local.DuePaymentEntity
import com.example.data.local.ExpenseCategory
import com.example.data.local.ExpenseEntity
import com.example.data.local.PartyType
import com.example.data.local.PaymentMethod
import com.example.data.local.PurchaseEntity
import com.example.data.local.SaleEntity
import com.example.data.local.SaleItemEntity
import com.example.data.local.UnitType
import com.example.data.local.VegetableCategory
import com.example.data.local.VegetableEntity
import com.example.data.local.WastageEntity
import com.example.data.local.WastageReason
import com.example.domain.BusinessCalculator
import org.junit.Assert.assertEquals
import org.junit.Test

class ExampleUnitTest {

  @Test
  fun `calculateLineTotal handles kg and grams accurately`() {
    val total = BusinessCalculator.calculateLineTotal(1500L, UnitType.KG, 40.0)
    assertEquals(60.0, total, 0.001)

    val grams250 = BusinessCalculator.calculateLineTotal(250L, UnitType.KG, 80.0)
    assertEquals(20.0, grams250, 0.001)
  }

  @Test
  fun `weighted average cost calculates accurately when buying at different prices`() {
    val newAvg =
      BusinessCalculator.calculateWeightedAverageCost(
        currentStockUnits = 10_000L, // 10 kg
        currentAvgCost = 30.0,
        addedUnits = 10_000L, // 10 kg
        addedLineTotal = 400.0, // 40/kg
        unitType = UnitType.KG,
      )
    assertEquals(35.0, newAvg, 0.001)
  }

  @Test
  fun `net profit deducts COGS wastage and operating expenses without double counting`() {
    val veg =
      VegetableEntity(
        id = 1L,
        name = "আলু",
        category = VegetableCategory.ROOT,
        unitType = UnitType.KG,
        stockUnits = 10_000L,
        avgCostPerUnit = 30.0,
        sellingPricePerUnit = 40.0,
      )
    val sale =
      SaleEntity(
        id = 1L,
        subtotal = 3000.0,
        discount = 0.0,
        totalAmount = 3000.0,
        paidAmount = 2500.0,
        dueAmount = 500.0,
        paymentMethod = PaymentMethod.CREDIT,
      )
    val saleItems =
      mapOf(
        1L to
          listOf(
            SaleItemEntity(
              id = 1L,
              saleId = 1L,
              vegetableId = 1L,
              vegetableName = "আলু",
              unitType = UnitType.KG,
              quantityUnits = 70_000L,
              sellingPricePerUnit = 42.85,
              costSnapshotPerUnit = 30.0, // COGS = 2100
              lineTotal = 3000.0,
            )
          )
      )
    val wastage =
      WastageEntity(
        id = 1L,
        vegetableId = 1L,
        vegetableName = "আলু",
        unitType = UnitType.KG,
        quantityUnits = 3333L,
        unitCostSnapshot = 30.0,
        lossAmount = 100.0,
        reason = WastageReason.ROTTEN,
      )
    val expense = ExpenseEntity(id = 1L, category = ExpenseCategory.PACKAGING, amount = 200.0)
    val purchase =
      PurchaseEntity(id = 1L, itemsTotal = 1000.0, extraCost = 50.0, paidAmount = 1000.0)
    val collection =
      DuePaymentEntity(
        id = 1L,
        partyId = 1L,
        partyName = "রহিম",
        partyType = PartyType.CUSTOMER,
        amount = 200.0,
      )

    val summary =
      BusinessCalculator.calculateFinancialSummary(
        sales = listOf(sale),
        saleItemsBySaleId = saleItems,
        purchases = listOf(purchase),
        expenses = listOf(expense),
        wastages = listOf(wastage),
        duePayments = listOf(collection),
        vegetables = listOf(veg),
      )

    assertEquals(3000.0, summary.netSales, 0.01)
    assertEquals(2100.0, summary.cogs, 0.01)
    assertEquals(900.0, summary.grossProfit, 0.01)
    assertEquals(100.0, summary.wastageLoss, 0.01)
    assertEquals(250.0, summary.operatingExpenses, 0.01) // 200 expense + 50 purchase extra
    assertEquals(550.0, summary.netProfit, 0.01) // 900 - 100 - 250
    assertEquals(2700.0, summary.cashInflow, 0.01) // 2500 + 200
    assertEquals(1250.0, summary.cashOutflow, 0.01) // 1000 + 50 + 200
    assertEquals(300.0, summary.inventoryValue, 0.01) // 10kg * 30
  }

  @Test
  fun `bengali formatting formats digits and quantities properly`() {
    assertEquals("১ কেজি ৫০০ গ্রাম", BusinessCalculator.formatQuantity(1500L, UnitType.KG, true))
    assertEquals("৳ ১,২৫০", BusinessCalculator.formatTaka(1250.0, true))
    assertEquals("৳ 1,250", BusinessCalculator.formatTaka(1250.0, false))
  }

  @Test
  fun `weighted average cost resets cleanly when prior stock was zero or negative`() {
    val newAvg =
      BusinessCalculator.calculateWeightedAverageCost(
        currentStockUnits = -1_000L, // stock deficit from BR-004 override
        currentAvgCost = 30.0,
        addedUnits = 5_000L,
        addedLineTotal = 225.0, // 45/kg
        unitType = UnitType.KG,
      )
    assertEquals(45.0, newAvg, 0.001)
  }

  @Test
  fun `salted pin hash verifies correct pin and rejects wrong pin`() {
    val stored = com.example.data.prefs.ShopPreferencesRepository.hashPin("4829")
    org.junit.Assert.assertTrue(stored.startsWith("sha256:"))
    org.junit.Assert.assertTrue(
      com.example.data.prefs.ShopPreferencesRepository.verifyPin("4829", stored)
    )
    org.junit.Assert.assertFalse(
      com.example.data.prefs.ShopPreferencesRepository.verifyPin("1234", stored)
    )
  }

  @Test
  fun `cancelled sales wastages expenses and purchases are excluded from financial summary`() {
    val veg =
      VegetableEntity(
        id = 1L,
        name = "পেঁয়াজ",
        category = VegetableCategory.ROOT,
        unitType = UnitType.KG,
        stockUnits = 5_000L,
        avgCostPerUnit = 60.0,
        sellingPricePerUnit = 80.0,
      )
    val cancelledSale =
      SaleEntity(
        id = 1L,
        subtotal = 800.0,
        totalAmount = 800.0,
        paidAmount = 800.0,
        isCancelled = true,
      )
    val cancelledWastage =
      WastageEntity(
        id = 1L,
        vegetableId = 1L,
        vegetableName = "পেঁয়াজ",
        unitType = UnitType.KG,
        quantityUnits = 1_000L,
        unitCostSnapshot = 60.0,
        lossAmount = 60.0,
        reason = WastageReason.ROTTEN,
        isCancelled = true,
      )
    val cancelledExpense =
      ExpenseEntity(id = 1L, category = ExpenseCategory.RENT, amount = 500.0, isCancelled = true)
    val cancelledPurchase =
      PurchaseEntity(
        id = 1L,
        itemsTotal = 600.0,
        extraCost = 50.0,
        paidAmount = 600.0,
        isCancelled = true,
      )

    val summary =
      BusinessCalculator.calculateFinancialSummary(
        sales = listOf(cancelledSale),
        saleItemsBySaleId = emptyMap(),
        purchases = listOf(cancelledPurchase),
        expenses = listOf(cancelledExpense),
        wastages = listOf(cancelledWastage),
        duePayments = emptyList(),
        vegetables = listOf(veg),
      )

    assertEquals(0.0, summary.netSales, 0.001)
    assertEquals(0.0, summary.wastageLoss, 0.001)
    assertEquals(0.0, summary.operatingExpenses, 0.001)
    assertEquals(0.0, summary.netProfit, 0.001)
    assertEquals(0.0, summary.cashInflow, 0.001)
    assertEquals(0.0, summary.cashOutflow, 0.001)
    assertEquals(300.0, summary.inventoryValue, 0.001)
  }

  @Test
  fun `calculateWastageLoss and negative deficit formatting handle edge cases`() {
    assertEquals(22.5, BusinessCalculator.calculateWastageLoss(500L, 45.0, UnitType.KG), 0.001)
    assertEquals(40.0, BusinessCalculator.calculateWastageLoss(2L, 20.0, UnitType.BUNCH), 0.001)
    assertEquals(0.0, BusinessCalculator.calculateWastageLoss(0L, 45.0, UnitType.KG), 0.001)
    assertEquals("-২ কেজি ৫০০ গ্রাম", BusinessCalculator.formatQuantity(-2500L, UnitType.KG, true))
    assertEquals("3 আঁটি", BusinessCalculator.formatQuantity(3L, UnitType.BUNCH, false))
    assertEquals("৫ পিস", BusinessCalculator.formatQuantity(5L, UnitType.PIECE, true))
  }
}
