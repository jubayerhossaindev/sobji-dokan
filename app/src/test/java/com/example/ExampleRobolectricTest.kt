package com.example

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.backup.BackupManager
import com.example.data.backup.SobjiKhataBackupPayload
import com.example.data.local.AppDatabase
import com.example.data.local.MovementType
import com.example.data.local.PartyType
import com.example.data.local.PaymentMethod
import com.example.data.local.PurchaseEntity
import com.example.data.local.PurchaseItemEntity
import com.example.data.local.SaleEntity
import com.example.data.local.SaleItemEntity
import com.example.data.local.UnitType
import com.example.data.local.VegetableCategory
import com.example.data.local.VegetableEntity
import com.example.data.local.WastageReason
import com.example.data.prefs.ShopPreferencesRepository
import com.example.data.repository.ShopRepository
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

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
  fun `read app_name string from context`() {
    val appName = context.getString(R.string.app_name)
    assertEquals("সবজি খাতা", appName)
  }

  @Test
  fun `purchase sale wastage and sale cancellation update stock atomically`() = runTest {
    val dao = db.shopDao()
    val vegId =
      dao.insertVegetable(
        VegetableEntity(
          name = "টমেটো",
          category = VegetableCategory.FRUIT_VEG,
          unitType = UnitType.KG,
          stockUnits = 0L,
          avgCostPerUnit = 0.0,
          sellingPricePerUnit = 60.0,
        )
      )

    // 1. Purchase 10 kg at 40/kg
    dao.completePurchaseAtomic(
      purchase = PurchaseEntity(itemsTotal = 400.0, paidAmount = 400.0),
      items =
        listOf(
          PurchaseItemEntity(
            purchaseId = 0L,
            vegetableId = vegId,
            vegetableName = "টমেটো",
            unitType = UnitType.KG,
            quantityUnits = 10_000L,
            unitCost = 40.0,
            lineTotal = 400.0,
          )
        ),
    )
    var veg = dao.getVegetableById(vegId)!!
    assertEquals(10_000L, veg.stockUnits)
    assertEquals(40.0, veg.avgCostPerUnit, 0.01)

    // 2. Sell 2.5 kg at 60/kg
    val saleId =
      dao.completeSaleAtomic(
        sale =
          SaleEntity(
            subtotal = 150.0,
            totalAmount = 150.0,
            paidAmount = 150.0,
            paymentMethod = PaymentMethod.CASH,
          ),
        items =
          listOf(
            SaleItemEntity(
              saleId = 0L,
              vegetableId = vegId,
              vegetableName = "টমেটো",
              unitType = UnitType.KG,
              quantityUnits = 2_500L,
              sellingPricePerUnit = 60.0,
              costSnapshotPerUnit = 40.0,
              lineTotal = 150.0,
            )
          ),
      )
    veg = dao.getVegetableById(vegId)!!
    assertEquals(7_500L, veg.stockUnits)

    // 3. Record 500g wastage
    dao.recordWastageAtomic(vegId, 500L, WastageReason.ROTTEN, "", System.currentTimeMillis())
    veg = dao.getVegetableById(vegId)!!
    assertEquals(7_000L, veg.stockUnits)

    // 4. Cancel sale -> stock restores by 2.5 kg; calling twice must be idempotent
    dao.cancelSaleAtomic(saleId)
    dao.cancelSaleAtomic(saleId)
    veg = dao.getVegetableById(vegId)!!
    assertEquals(9_500L, veg.stockUnits)
  }

  @Test
  fun `duplicate vegetable name is rejected and stock adjustment logs movement`() = runTest {
    val repo = ShopRepository(db.shopDao())
    val first =
      repo.saveVegetable(
        existingId = null,
        name = "আলু",
        category = VegetableCategory.ROOT,
        unitType = UnitType.KG,
        avgCost = 30.0,
        sellingPrice = 40.0,
        lowStockThreshold = 2000L,
        initialStock = 5000L,
      )
    assertTrue(first.isSuccess)

    val duplicate =
      repo.saveVegetable(
        existingId = null,
        name = " আলু ",
        category = VegetableCategory.ROOT,
        unitType = UnitType.KG,
        avgCost = 32.0,
        sellingPrice = 42.0,
        lowStockThreshold = 2000L,
        initialStock = 1000L,
      )
    assertTrue(duplicate.isFailure)

    val veg = db.shopDao().findVegetableByName("আলু")!!
    repo.adjustStock(veg.id, 4200L, "বাস্তব ওজন মিলানো")
    val updated = db.shopDao().getVegetableById(veg.id)!!
    assertEquals(4200L, updated.stockUnits)
    val movements = db.shopDao().getAllMovementsSnapshot()
    assertEquals(1, movements.size)
    assertEquals(MovementType.ADJUSTMENT, movements.first().movementType)
    assertEquals(-800L, movements.first().deltaUnits)
  }

  @Test
  fun `wastage cancel and purchase cancel are idempotent and restore stock`() = runTest {
    val dao = db.shopDao()
    val vegId =
      dao.insertVegetable(
        VegetableEntity(
          name = "বেগুন",
          category = VegetableCategory.FRUIT_VEG,
          unitType = UnitType.KG,
          stockUnits = 0L,
          avgCostPerUnit = 35.0,
          sellingPricePerUnit = 50.0,
        )
      )
    val purchaseId =
      dao.completePurchaseAtomic(
        purchase = PurchaseEntity(itemsTotal = 350.0, paidAmount = 350.0),
        items =
          listOf(
            PurchaseItemEntity(
              purchaseId = 0L,
              vegetableId = vegId,
              vegetableName = "বেগুন",
              unitType = UnitType.KG,
              quantityUnits = 10_000L,
              unitCost = 35.0,
              lineTotal = 350.0,
            )
          ),
      )

    dao.recordWastageAtomic(vegId, 1_000L, WastageReason.ROTTEN, "নষ্ট", System.currentTimeMillis())
    val wastageId = dao.getAllWastagesSnapshot().first().id
    assertEquals(9_000L, dao.getVegetableById(vegId)!!.stockUnits)

    dao.cancelWastageAtomic(wastageId)
    dao.cancelWastageAtomic(wastageId)
    assertEquals(10_000L, dao.getVegetableById(vegId)!!.stockUnits)
    assertTrue(dao.getWastageById(wastageId)!!.isCancelled)

    dao.cancelPurchaseAtomic(purchaseId)
    dao.cancelPurchaseAtomic(purchaseId)
    assertEquals(0L, dao.getVegetableById(vegId)!!.stockUnits)
    assertTrue(dao.getPurchaseById(purchaseId)!!.isCancelled)
  }

  @Test
  fun `backup export validate and atomic restore roundtrip preserves data and rejects corrupted json`() =
    runTest {
      val dao = db.shopDao()
      val prefsRepo = ShopPreferencesRepository(context)
      prefsRepo.saveInitialSetup("টেস্ট সবজি ঘর", "করিম", true)

      val vegId =
        dao.insertVegetable(
          VegetableEntity(
            name = "শসা",
            category = VegetableCategory.FRUIT_VEG,
            unitType = UnitType.KG,
            stockUnits = 8_000L,
            avgCostPerUnit = 30.0,
            sellingPricePerUnit = 45.0,
          )
        )
      dao.insertParty(
        com.example.data.local.PartyEntity(
          name = "রহিম",
          phone = "01711",
          partyType = PartyType.CUSTOMER,
        )
      )
      dao.completeSaleAtomic(
        sale =
          SaleEntity(
            subtotal = 90.0,
            totalAmount = 90.0,
            paidAmount = 90.0,
            paymentMethod = PaymentMethod.CASH,
          ),
        items =
          listOf(
            SaleItemEntity(
              saleId = 0L,
              vegetableId = vegId,
              vegetableName = "শসা",
              unitType = UnitType.KG,
              quantityUnits = 2_000L,
              sellingPricePerUnit = 45.0,
              costSnapshotPerUnit = 30.0,
              lineTotal = 90.0,
            )
          ),
      )

      val backupManager = BackupManager(context, dao, prefsRepo)
      val exportFile = File.createTempFile("sobjikhata_backup_test", ".json", context.cacheDir)
      val exportUri = Uri.fromFile(exportFile)

      val exportResult = backupManager.exportToUri(exportUri)
      assertTrue(exportResult.isSuccess)
      assertTrue(exportFile.readText().contains(SobjiKhataBackupPayload.SCHEMA_TAG))

      // Wipe DB to simulate new phone or pre-restore state
      dao.clearMovements()
      dao.clearSaleItems()
      dao.clearSales()
      dao.clearParties()
      dao.clearVegetables()
      assertEquals(0, dao.getAllVegetablesSnapshot().size)

      val validated = backupManager.validateBackupUri(exportUri)
      assertTrue(validated.isSuccess)
      val restoreResult = backupManager.restoreFromPayload(validated.getOrThrow())
      assertTrue(restoreResult.isSuccess)

      assertEquals(1, dao.getAllVegetablesSnapshot().size)
      assertEquals("শসা", dao.getAllVegetablesSnapshot().first().name)
      assertEquals(6_000L, dao.getAllVegetablesSnapshot().first().stockUnits)
      assertEquals(1, dao.getAllSalesSnapshot().size)
      assertEquals("টেস্ট সবজি ঘর", prefsRepo.preferencesFlow.first().shopName)

      // Corrupted JSON and mismatched schema tag must fail validation without mutating DB
      val badFile = File.createTempFile("bad_backup", ".json", context.cacheDir)
      badFile.writeText("{ \"schemaTag\": \"wrong_app\", \"version\": 99 }")
      val badValidation = backupManager.validateBackupUri(Uri.fromFile(badFile))
      assertTrue(badValidation.isFailure)

      val nonJsonFile = File.createTempFile("not_json", ".txt", context.cacheDir)
      nonJsonFile.writeText("plain text content")
      assertTrue(backupManager.validateBackupUri(Uri.fromFile(nonJsonFile)).isFailure)

      exportFile.delete()
      badFile.delete()
      nonJsonFile.delete()
    }
}
