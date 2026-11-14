package com.example.qa

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.MovementType
import com.example.data.local.PaymentMethod
import com.example.data.local.PurchaseEntity
import com.example.data.local.PurchaseItemEntity
import com.example.data.local.SaleEntity
import com.example.data.local.SaleItemEntity
import com.example.data.local.UnitType
import com.example.data.local.VegetableCategory
import com.example.data.local.VegetableEntity
import com.example.data.local.WastageReason
import com.example.data.repository.ShopRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InventoryConsistencyQaTest {

  private lateinit var context: Context
  private lateinit var db: AppDatabase

  @Before
  fun setup() {
    context = ApplicationProvider.getApplicationContext()
    db =
      Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `TC-INV-01 inventory equation and movement log continuity hold across purchases sales wastage cancellations and adjustments`() =
    runTest {
      val dao = db.shopDao()
      val potatoId =
        dao.insertVegetable(
          VegetableEntity(
            name = "আলু",
            category = VegetableCategory.ROOT,
            unitType = UnitType.KG,
            stockUnits = 5_000L, // Opening = 5kg
            avgCostPerUnit = 30.0,
            sellingPricePerUnit = 40.0,
          )
        )

      // Purchase +15kg @ 40/kg -> stock 20kg, avgCost = (5*30 + 15*40)/20 = 750/20 = 37.5
      val p1 =
        dao.completePurchaseAtomic(
          PurchaseEntity(itemsTotal = 600.0, paidAmount = 600.0),
          listOf(
            PurchaseItemEntity(
              purchaseId = 0L,
              vegetableId = potatoId,
              vegetableName = "আলু",
              unitType = UnitType.KG,
              quantityUnits = 15_000L,
              unitCost = 40.0,
              lineTotal = 600.0,
            )
          ),
        )
      assertEquals(20_000L, dao.getVegetableById(potatoId)!!.stockUnits)
      assertEquals(37.5, dao.getVegetableById(potatoId)!!.avgCostPerUnit, 0.001)

      // Sale -3.5kg -> stock 16.5kg
      val s1 =
        dao.completeSaleAtomic(
          SaleEntity(subtotal = 140.0, totalAmount = 140.0, paidAmount = 140.0),
          listOf(
            SaleItemEntity(
              saleId = 0L,
              vegetableId = potatoId,
              vegetableName = "আলু",
              unitType = UnitType.KG,
              quantityUnits = 3_500L,
              sellingPricePerUnit = 40.0,
              costSnapshotPerUnit = 0.0, // DAO snapshots current avgCost 37.5
              lineTotal = 140.0,
            )
          ),
        )
      assertEquals(37.5, dao.getSaleItems(s1).first().costSnapshotPerUnit, 0.001)
      assertEquals(16_500L, dao.getVegetableById(potatoId)!!.stockUnits)

      // Wastage -1.25kg -> stock 15.25kg
      dao.recordWastageAtomic(
        potatoId,
        1_250L,
        WastageReason.ROTTEN,
        "পচা",
        System.currentTimeMillis(),
      )
      assertEquals(15_250L, dao.getVegetableById(potatoId)!!.stockUnits)

      // Cancel Sale (+3.5kg) -> stock 18.75kg
      dao.cancelSaleAtomic(s1)
      assertEquals(18_750L, dao.getVegetableById(potatoId)!!.stockUnits)

      // Wastage Cancel (+1.25kg) -> stock 20.0kg
      val wId = dao.getAllWastagesSnapshot().first().id
      dao.cancelWastageAtomic(wId)
      assertEquals(20_000L, dao.getVegetableById(potatoId)!!.stockUnits)

      // Manual physical adjustment to 19.6kg (-400g shrinkage)
      dao.adjustStockAtomic(potatoId, 19_600L, "ওজন কমেছে")
      assertEquals(19_600L, dao.getVegetableById(potatoId)!!.stockUnits)

      // Cancel purchase (-15kg) -> stock 4.6kg
      dao.cancelPurchaseAtomic(p1)
      val finalVeg = dao.getVegetableById(potatoId)!!
      assertEquals(4_600L, finalVeg.stockUnits)

      // Verify movement delta chain equals (finalStock - openingStock) = -400g
      val movements = dao.getAllMovementsSnapshot()
      assertEquals(7, movements.size)
      assertEquals(-400L, movements.sumOf { it.deltaUnits })
      assertTrue(movements.any { it.movementType == MovementType.PURCHASE })
      assertTrue(movements.any { it.movementType == MovementType.SALE })
      assertTrue(movements.any { it.movementType == MovementType.WASTAGE })
      assertTrue(movements.any { it.movementType == MovementType.ADJUSTMENT })
    }

  @Test
  fun `TC-INV-02 multi-item single sale and repeat customer independent bills (Section 7C & BR-005)`() =
    runTest {
      val dao = db.shopDao()
      val potatoId =
        dao.insertVegetable(
          VegetableEntity(
            name = "আলু",
            category = VegetableCategory.ROOT,
            unitType = UnitType.KG,
            stockUnits = 10_000L,
            avgCostPerUnit = 30.0,
            sellingPricePerUnit = 40.0,
          )
        )
      val onionId =
        dao.insertVegetable(
          VegetableEntity(
            name = "পেঁয়াজ",
            category = VegetableCategory.ROOT,
            unitType = UnitType.KG,
            stockUnits = 10_000L,
            avgCostPerUnit = 60.0,
            sellingPricePerUnit = 80.0,
          )
        )
      val tomatoId =
        dao.insertVegetable(
          VegetableEntity(
            name = "টমেটো",
            category = VegetableCategory.FRUIT_VEG,
            unitType = UnitType.KG,
            stockUnits = 10_000L,
            avgCostPerUnit = 45.0,
            sellingPricePerUnit = 60.0,
          )
        )

      // Single customer buys 2kg potato (80) + 1kg onion (80) + 1kg tomato (60) = 220 -> 1 Sale, 3
      // SaleItems
      val sale1Id =
        dao.completeSaleAtomic(
          SaleEntity(
            customerId = 1L,
            customerName = "রহিম",
            subtotal = 220.0,
            totalAmount = 220.0,
            paidAmount = 220.0,
            paymentMethod = PaymentMethod.CASH,
          ),
          listOf(
            SaleItemEntity(
              saleId = 0L,
              vegetableId = potatoId,
              vegetableName = "আলু",
              unitType = UnitType.KG,
              quantityUnits = 2_000L,
              sellingPricePerUnit = 40.0,
              costSnapshotPerUnit = 30.0,
              lineTotal = 80.0,
            ),
            SaleItemEntity(
              saleId = 0L,
              vegetableId = onionId,
              vegetableName = "পেঁয়াজ",
              unitType = UnitType.KG,
              quantityUnits = 1_000L,
              sellingPricePerUnit = 80.0,
              costSnapshotPerUnit = 60.0,
              lineTotal = 80.0,
            ),
            SaleItemEntity(
              saleId = 0L,
              vegetableId = tomatoId,
              vegetableName = "টমেটো",
              unitType = UnitType.KG,
              quantityUnits = 1_000L,
              sellingPricePerUnit = 60.0,
              costSnapshotPerUnit = 45.0,
              lineTotal = 60.0,
            ),
          ),
        )

      // Same customer returns later for 1kg potato -> creates second independent SaleEntity
      // (BR-005)
      val sale2Id =
        dao.completeSaleAtomic(
          SaleEntity(
            customerId = 1L,
            customerName = "রহিম",
            subtotal = 40.0,
            totalAmount = 40.0,
            paidAmount = 40.0,
            paymentMethod = PaymentMethod.CASH,
          ),
          listOf(
            SaleItemEntity(
              saleId = 0L,
              vegetableId = potatoId,
              vegetableName = "আলু",
              unitType = UnitType.KG,
              quantityUnits = 1_000L,
              sellingPricePerUnit = 40.0,
              costSnapshotPerUnit = 30.0,
              lineTotal = 40.0,
            )
          ),
        )

      assertEquals(2, dao.getAllSalesSnapshot().size)
      assertTrue(sale1Id != sale2Id)
      assertEquals(3, dao.getSaleItems(sale1Id).size)
      assertEquals(1, dao.getSaleItems(sale2Id).size)
      assertEquals(7_000L, dao.getVegetableById(potatoId)!!.stockUnits)
      assertEquals(9_000L, dao.getVegetableById(onionId)!!.stockUnits)
      assertEquals(9_000L, dao.getVegetableById(tomatoId)!!.stockUnits)
    }

  @Test
  fun `TC-INV-03 out-of-stock deficit override recovers cleanly on purchase and archived vegetables stay hidden from POS`() =
    runTest {
      val repo = ShopRepository(db.shopDao())
      val dao = db.shopDao()

      val lauId =
        dao.insertVegetable(
          VegetableEntity(
            name = "লাউ",
            category = VegetableCategory.FRUIT_VEG,
            unitType = UnitType.PIECE,
            stockUnits = 1L,
            avgCostPerUnit = 35.0,
            sellingPricePerUnit = 50.0,
          )
        )

      // Sell 3 pieces when only 1 piece is in stock -> deficit -2 (BR-004)
      repo.completeSale(
        customerId = null,
        customerName = "সাধারণ ক্রেতা",
        subtotal = 150.0,
        discount = 0.0,
        totalAmount = 150.0,
        paidAmount = 150.0,
        dueAmount = 0.0,
        paymentMethod = PaymentMethod.CASH,
        notes = "",
        items =
          listOf(
            SaleItemEntity(
              saleId = 0L,
              vegetableId = lauId,
              vegetableName = "লাউ",
              unitType = UnitType.PIECE,
              quantityUnits = 3L,
              sellingPricePerUnit = 50.0,
              costSnapshotPerUnit = 35.0,
              lineTotal = 150.0,
            )
          ),
      )
      assertEquals(-2L, dao.getVegetableById(lauId)!!.stockUnits)

      // Purchase 10 pieces @ 42 -> stock becomes 8, avgCost resets to 42.0 (not distorted by
      // negative stock)
      repo.completePurchase(
        supplierId = null,
        supplierName = "নগদ বাজার",
        itemsTotal = 420.0,
        extraCost = 0.0,
        paidAmount = 420.0,
        dueAmount = 0.0,
        notes = "",
        timestamp = System.currentTimeMillis(),
        items =
          listOf(
            PurchaseItemEntity(
              purchaseId = 0L,
              vegetableId = lauId,
              vegetableName = "লাউ",
              unitType = UnitType.PIECE,
              quantityUnits = 10L,
              unitCost = 42.0,
              lineTotal = 420.0,
            )
          ),
      )
      val recovered = dao.getVegetableById(lauId)!!
      assertEquals(8L, recovered.stockUnits)
      assertEquals(42.0, recovered.avgCostPerUnit, 0.001)

      // Archiving removes from activeVegetables while retaining in allVegetables
      repo.setVegetableArchived(recovered, true)
      assertFalse(repo.activeVegetables.first().any { it.id == lauId })
      assertTrue(repo.allVegetables.first().any { it.id == lauId && it.isArchived })
    }
}
