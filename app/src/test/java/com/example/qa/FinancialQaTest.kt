package com.example.qa

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
import org.junit.Assert.assertTrue
import org.junit.Test

class FinancialQaTest {

  @Test
  fun `TC-FIN-01 calculateLineTotal handles fractional grams HALF_UP and invalid bounds`() {
    assertEquals(33.3, BusinessCalculator.calculateLineTotal(333L, UnitType.KG, 100.0), 0.001)
    assertEquals(20.0, BusinessCalculator.calculateLineTotal(250L, UnitType.KG, 80.0), 0.001)
    assertEquals(150.0, BusinessCalculator.calculateLineTotal(3L, UnitType.PIECE, 50.0), 0.001)
    assertEquals(62.5, BusinessCalculator.calculateLineTotal(5L, UnitType.BUNCH, 12.5), 0.001)

    assertEquals(0.0, BusinessCalculator.calculateLineTotal(0L, UnitType.KG, 50.0), 0.001)
    assertEquals(0.0, BusinessCalculator.calculateLineTotal(-500L, UnitType.KG, 50.0), 0.001)
    assertEquals(0.0, BusinessCalculator.calculateLineTotal(1000L, UnitType.KG, 0.0), 0.001)
    assertEquals(0.0, BusinessCalculator.calculateLineTotal(1000L, UnitType.KG, -40.0), 0.001)
  }

  @Test
  fun `TC-FIN-02 multi-tier weighted average cost across 3 purchases and negative reset`() {
    // Tier 1: empty stock -> buy 10 kg @ 30 = 300 -> avg 30
    val avg1 =
      BusinessCalculator.calculateWeightedAverageCost(
        currentStockUnits = 0L,
        currentAvgCost = 0.0,
        addedUnits = 10_000L,
        addedLineTotal = 300.0,
        unitType = UnitType.KG,
      )
    assertEquals(30.0, avg1, 0.001)

    // Tier 2: 10 kg @ 30 + buy 10 kg @ 40 = 700 / 20 = 35
    val avg2 =
      BusinessCalculator.calculateWeightedAverageCost(
        currentStockUnits = 10_000L,
        currentAvgCost = avg1,
        addedUnits = 10_000L,
        addedLineTotal = 400.0,
        unitType = UnitType.KG,
      )
    assertEquals(35.0, avg2, 0.001)

    // Tier 3: after selling 15kg (5kg left @ 35 = 175), buy 10kg @ 50 (500) -> 675 / 15 = 45
    val avg3 =
      BusinessCalculator.calculateWeightedAverageCost(
        currentStockUnits = 5_000L,
        currentAvgCost = avg2,
        addedUnits = 10_000L,
        addedLineTotal = 500.0,
        unitType = UnitType.KG,
      )
    assertEquals(45.0, avg3, 0.001)

    // Deficit stock reset: -2kg old avg 45 + buy 8kg @ 55 (440) -> resets to 55.0
    val avgReset =
      BusinessCalculator.calculateWeightedAverageCost(
        currentStockUnits = -2_000L,
        currentAvgCost = avg3,
        addedUnits = 8_000L,
        addedLineTotal = 440.0,
        unitType = UnitType.KG,
      )
    assertEquals(55.0, avgReset, 0.001)
  }

  @Test
  fun `TC-FIN-03 full PnL waterfall separates Cash Flow vs Net Profit and ignores archived or negative inventory`() {
    val activeVeg1 =
      VegetableEntity(
        id = 1L,
        name = "আলু",
        category = VegetableCategory.ROOT,
        unitType = UnitType.KG,
        stockUnits = 12_000L,
        avgCostPerUnit = 32.5, // value = 390.0
        sellingPricePerUnit = 45.0,
      )
    val deficitVeg2 =
      VegetableEntity(
        id = 2L,
        name = "পেঁয়াজ",
        category = VegetableCategory.ROOT,
        unitType = UnitType.KG,
        stockUnits = -1_500L, // negative stock must count as 0 in inventory valuation (BR-012)
        avgCostPerUnit = 70.0,
        sellingPricePerUnit = 90.0,
      )
    val archivedVeg3 =
      VegetableEntity(
        id = 3L,
        name = "কচু",
        category = VegetableCategory.ROOT,
        unitType = UnitType.KG,
        stockUnits = 5_000L,
        avgCostPerUnit = 40.0,
        sellingPricePerUnit = 60.0,
        isArchived = true, // archived must not be counted in active valuation
      )

    // Sale 1: Cash sale 2kg @ 45 = 90, discount 10 -> total 80, paid 80, costSnapshot = 30 -> COGS
    // = 60
    // Sale 2: Full credit sale 500 = 500, paid 0, costSnapshot = 350 -> COGS = 350
    // Sale 3: Cancelled sale -> ignored
    val sales =
      listOf(
        SaleEntity(
          id = 1L,
          subtotal = 90.0,
          discount = 10.0,
          totalAmount = 80.0,
          paidAmount = 80.0,
        ),
        SaleEntity(
          id = 2L,
          customerId = 10L,
          customerName = "করিম",
          subtotal = 500.0,
          discount = 0.0,
          totalAmount = 500.0,
          paidAmount = 0.0,
          dueAmount = 500.0,
          paymentMethod = PaymentMethod.CREDIT,
        ),
        SaleEntity(
          id = 3L,
          subtotal = 1000.0,
          totalAmount = 1000.0,
          paidAmount = 1000.0,
          isCancelled = true,
        ),
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
              quantityUnits = 2_000L,
              sellingPricePerUnit = 45.0,
              costSnapshotPerUnit = 30.0,
              lineTotal = 90.0,
            )
          ),
        2L to
          listOf(
            SaleItemEntity(
              id = 2L,
              saleId = 2L,
              vegetableId = 1L,
              vegetableName = "আলু",
              unitType = UnitType.KG,
              quantityUnits = 10_000L,
              sellingPricePerUnit = 50.0,
              costSnapshotPerUnit = 35.0,
              lineTotal = 500.0,
            )
          ),
      )

    // Purchase: 1000 itemsTotal, paid 400 (due 600), extraCost 75
    val purchases =
      listOf(
        PurchaseEntity(
          id = 1L,
          itemsTotal = 1000.0,
          extraCost = 75.0,
          paidAmount = 400.0,
          dueAmount = 600.0,
        )
      )
    val expenses = listOf(ExpenseEntity(id = 1L, category = ExpenseCategory.RENT, amount = 125.0))
    val wastages =
      listOf(
        WastageEntity(
          id = 1L,
          vegetableId = 1L,
          vegetableName = "আলু",
          unitType = UnitType.KG,
          quantityUnits = 1_000L,
          unitCostSnapshot = 32.5,
          lossAmount = 32.5,
          reason = WastageReason.ROTTEN,
        )
      )
    // DuePayments: customer paid 150, supplier paid 200, plus a cancelled 999 payment
    val duePayments =
      listOf(
        DuePaymentEntity(
          id = 1L,
          partyId = 10L,
          partyName = "করিম",
          partyType = PartyType.CUSTOMER,
          amount = 150.0,
        ),
        DuePaymentEntity(
          id = 2L,
          partyId = 20L,
          partyName = "হাজী",
          partyType = PartyType.SUPPLIER,
          amount = 200.0,
        ),
        DuePaymentEntity(
          id = 3L,
          partyId = 10L,
          partyName = "করিম",
          partyType = PartyType.CUSTOMER,
          amount = 999.0,
          isCancelled = true,
        ),
      )

    val summary =
      BusinessCalculator.calculateFinancialSummary(
        sales = sales,
        saleItemsBySaleId = saleItems,
        purchases = purchases,
        expenses = expenses,
        wastages = wastages,
        duePayments = duePayments,
        vegetables = listOf(activeVeg1, deficitVeg2, archivedVeg3),
      )

    // Net Sales = 80 + 500 = 580
    assertEquals(580.0, summary.netSales, 0.01)
    // COGS = 60 + 350 = 410 (not changed even though activeVeg1.avgCostPerUnit is now 32.5)
    assertEquals(410.0, summary.cogs, 0.01)
    // Gross Profit = 580 - 410 = 170
    assertEquals(170.0, summary.grossProfit, 0.01)
    // Wastage = 32.5
    assertEquals(32.5, summary.wastageLoss, 0.01)
    // Expenses = 125 + 75 purchase extraCost = 200
    assertEquals(200.0, summary.operatingExpenses, 0.01)
    assertEquals(75.0, summary.purchaseExtraExpenses, 0.01)
    // Net Profit = 170 - 32.5 - 200 = -62.5 (net loss)
    assertEquals(-62.5, summary.netProfit, 0.01)
    // Cash Inflow = 80 (sale1) + 0 (sale2 credit) + 150 (customer collection) = 230
    assertEquals(230.0, summary.cashInflow, 0.01)
    // Cash Outflow = 400 (purchase paid) + 75 (extraCost) + 125 (expense) + 200 (supplier due paid)
    // = 800
    assertEquals(800.0, summary.cashOutflow, 0.01)
    // Net Cash Flow = 230 - 800 = -570
    assertEquals(-570.0, summary.netCashFlow, 0.01)
    // Inventory Value = 12 * 32.5 = 390.0 (deficit and archived excluded)
    assertEquals(390.0, summary.inventoryValue, 0.01)
    assertEquals(2, summary.activeSalesCount)
    assertEquals(1, summary.activePurchasesCount)
  }

  @Test
  fun `TC-FIN-04 large scale monthly totals and formatEditableNumber preserve precision`() {
    val largeSales =
      (1L..1000L).map { idx ->
        SaleEntity(
          id = idx,
          subtotal = 1500.75,
          discount = 0.75,
          totalAmount = 1500.0,
          paidAmount = 1500.0,
        )
      }
    val largeItems =
      (1L..1000L).associateWith { idx ->
        listOf(
          SaleItemEntity(
            id = idx,
            saleId = idx,
            vegetableId = 1L,
            vegetableName = "আলু",
            unitType = UnitType.KG,
            quantityUnits = 25_000L,
            sellingPricePerUnit = 60.0,
            costSnapshotPerUnit = 42.33,
            lineTotal = 1500.0,
          )
        )
      }
    val summary =
      BusinessCalculator.calculateFinancialSummary(
        sales = largeSales,
        saleItemsBySaleId = largeItems,
        purchases = emptyList(),
        expenses = emptyList(),
        wastages = emptyList(),
        duePayments = emptyList(),
        vegetables = emptyList(),
      )

    assertEquals(1_500_000.0, summary.netSales, 0.01)
    assertEquals(1_058_250.0, summary.cogs, 0.01)
    assertEquals(441_750.0, summary.netProfit, 0.01)

    // Precision preservation on BUG-002 helper
    assertEquals("42.67", BusinessCalculator.formatEditableNumber(42.67))
    assertEquals("55.5", BusinessCalculator.formatEditableNumber(55.50))
    assertEquals("40", BusinessCalculator.formatEditableNumber(40.0))
    assertEquals("", BusinessCalculator.formatEditableNumber(0.0))
    assertEquals("", BusinessCalculator.formatEditableNumber(Double.NaN))
    assertTrue(BusinessCalculator.formatTaka(-62.5, true).startsWith("-৳"))
    assertEquals("+৳ ৫৫০", BusinessCalculator.formatTaka(550.0, true, showSign = true))
  }

  @Test
  fun `TC-FIN-05 expectedCashInDrawer separates physical cash from mobile payments and credit`() {
    val sales =
      listOf(
        SaleEntity(
          id = 1L,
          subtotal = 500.0,
          totalAmount = 500.0,
          paidAmount = 500.0,
          paymentMethod = PaymentMethod.CASH,
        ),
        SaleEntity(
          id = 2L,
          subtotal = 300.0,
          totalAmount = 300.0,
          paidAmount = 300.0,
          paymentMethod = PaymentMethod.MOBILE,
        ),
        SaleEntity(
          id = 3L,
          customerId = 10L,
          customerName = "রহিম",
          subtotal = 400.0,
          totalAmount = 400.0,
          paidAmount = 100.0,
          dueAmount = 300.0,
          paymentMethod = PaymentMethod.CREDIT,
        ),
      )
    val purchases =
      listOf(
        PurchaseEntity(
          id = 1L,
          itemsTotal = 300.0,
          extraCost = 50.0,
          paidAmount = 200.0,
          dueAmount = 100.0,
        )
      )
    val expenses =
      listOf(
        ExpenseEntity(
          id = 1L,
          category = ExpenseCategory.PACKAGING,
          amount = 40.0,
          paymentMethod = PaymentMethod.CASH,
        ),
        ExpenseEntity(
          id = 2L,
          category = ExpenseCategory.UTILITY,
          amount = 60.0,
          paymentMethod = PaymentMethod.MOBILE,
        ),
      )
    val duePayments =
      listOf(
        DuePaymentEntity(
          id = 1L,
          partyId = 10L,
          partyName = "রহিম",
          partyType = PartyType.CUSTOMER,
          amount = 150.0,
          paymentMethod = PaymentMethod.CASH,
        ),
        DuePaymentEntity(
          id = 2L,
          partyId = 10L,
          partyName = "রহিম",
          partyType = PartyType.CUSTOMER,
          amount = 100.0,
          paymentMethod = PaymentMethod.MOBILE,
        ),
        DuePaymentEntity(
          id = 3L,
          partyId = 20L,
          partyName = "আড়ত",
          partyType = PartyType.SUPPLIER,
          amount = 80.0,
          paymentMethod = PaymentMethod.CASH,
        ),
        DuePaymentEntity(
          id = 4L,
          partyId = 20L,
          partyName = "আড়ত",
          partyType = PartyType.SUPPLIER,
          amount = 70.0,
          paymentMethod = PaymentMethod.MOBILE,
        ),
      )

    val summary =
      BusinessCalculator.calculateFinancialSummary(
        sales = sales,
        saleItemsBySaleId = emptyMap(),
        purchases = purchases,
        expenses = expenses,
        wastages = emptyList(),
        duePayments = duePayments,
        vegetables = emptyList(),
      )

    // Total inflow = 500 + 300 + 100 + 150 + 100 = 1150; mobile inflow = 300 + 100 = 400 ->
    // physical cash in = 750
    assertEquals(1150.0, summary.cashInflow, 0.01)
    assertEquals(400.0, summary.mobileSalesAndCollections, 0.01)
    assertEquals(750.0, summary.cashSalesAndCollections, 0.01)

    // Total outflow = (200 + 50) + (40 + 60) + (80 + 70) = 500; mobile out = 60 + 70 = 130 ->
    // physical cash out = 370
    assertEquals(500.0, summary.cashOutflow, 0.01)
    assertEquals(370.0, summary.cashOutflowOnly, 0.01)

    // Expected drawer cash = 750 - 370 = 380
    assertEquals(380.0, summary.expectedCashInDrawer, 0.01)
  }
}
