package com.example.qa

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.data.backup.BackupManager
import com.example.data.backup.SobjiKhataBackupPayload
import com.example.data.local.AppDatabase
import com.example.data.local.ExpenseCategory
import com.example.data.local.ExpenseEntity
import com.example.data.local.PartyEntity
import com.example.data.local.PartyType
import com.example.data.local.SaleEntity
import com.example.data.local.SaleItemEntity
import com.example.data.local.UnitType
import com.example.data.local.VegetableCategory
import com.example.data.local.VegetableEntity
import com.example.data.prefs.ShopPreferencesRepository
import com.example.data.repository.ShopRepository
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DatabaseAndSecurityQaTest {

  private lateinit var context: Context
  private lateinit var db: AppDatabase

  @Before
  fun setup() {
    context = ApplicationProvider.getApplicationContext()
    runBlocking { ShopPreferencesRepository(context).resetForTest() }
    db =
      Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
  }

  @After
  fun tearDown() {
    db.close()
    runBlocking { ShopPreferencesRepository(context).resetForTest() }
  }

  @Test
  fun `TC-DB-01 transaction rollback preserves prior state cleanly on mid-flight exception`() =
    runTest {
      val dao = db.shopDao()
      val vegId =
        dao.insertVegetable(
          VegetableEntity(
            name = "পটল",
            category = VegetableCategory.FRUIT_VEG,
            unitType = UnitType.KG,
            stockUnits = 4_000L,
            avgCostPerUnit = 40.0,
            sellingPricePerUnit = 55.0,
          )
        )

      var threw = false
      try {
        db.withTransaction {
          dao.completeSaleAtomic(
            SaleEntity(subtotal = 110.0, totalAmount = 110.0, paidAmount = 110.0),
            listOf(
              SaleItemEntity(
                saleId = 0L,
                vegetableId = vegId,
                vegetableName = "পটল",
                unitType = UnitType.KG,
                quantityUnits = 2_000L,
                sellingPricePerUnit = 55.0,
                costSnapshotPerUnit = 40.0,
                lineTotal = 110.0,
              )
            ),
          )
          throw IllegalStateException("Simulated crash mid-transaction")
        }
      } catch (_: IllegalStateException) {
        threw = true
      }

      assertTrue(threw)
      // Entire transaction rolled back: stock unchanged, 0 sales, 0 saleItems, 0 movements
      assertEquals(4_000L, dao.getVegetableById(vegId)!!.stockUnits)
      assertEquals(0, dao.getAllSalesSnapshot().size)
      assertEquals(0, dao.getAllSaleItemsSnapshot().size)
      assertEquals(0, dao.getAllMovementsSnapshot().size)
    }

  @Test
  fun `TC-DB-02 validation rules reject blank name, duplicate name with whitespace, and non-positive selling price`() =
    runTest {
      val repo = ShopRepository(db.shopDao())

      assertTrue(
        repo
          .saveVegetable(null, "   ", VegetableCategory.LEAFY, UnitType.BUNCH, 10.0, 20.0, 5L, 10L)
          .isFailure
      )
      assertTrue(
        repo
          .saveVegetable(
            null,
            "লাল শাক",
            VegetableCategory.LEAFY,
            UnitType.BUNCH,
            10.0,
            0.0,
            5L,
            10L,
          )
          .isFailure
      )
      assertTrue(
        repo
          .saveVegetable(
            null,
            "লাল শাক",
            VegetableCategory.LEAFY,
            UnitType.BUNCH,
            10.0,
            -5.0,
            5L,
            10L,
          )
          .isFailure
      )

      assertTrue(
        repo
          .saveVegetable(
            null,
            "লাল শাক",
            VegetableCategory.LEAFY,
            UnitType.BUNCH,
            10.0,
            20.0,
            5L,
            10L,
          )
          .isSuccess
      )
      // Duplicate trimmed
      assertTrue(
        repo
          .saveVegetable(
            null,
            "  লাল শাক  ",
            VegetableCategory.LEAFY,
            UnitType.BUNCH,
            12.0,
            22.0,
            5L,
            10L,
          )
          .isFailure
      )
    }

  @Test
  fun `TC-SEC-01 salted SHA-256 PIN uses unique random salts and never leaks into JSON backup`() =
    runTest {
      val hash1 = ShopPreferencesRepository.hashPin("7391")
      val hash2 = ShopPreferencesRepository.hashPin("7391")
      assertTrue(hash1.startsWith("sha256:"))
      assertNotEquals(hash1, hash2) // distinct 16-byte salts
      assertTrue(ShopPreferencesRepository.verifyPin("7391", hash1))
      assertTrue(ShopPreferencesRepository.verifyPin("7391", hash2))
      assertFalse(ShopPreferencesRepository.verifyPin("7392", hash1))

      // Export backup while PIN is enabled -> PIN hash MUST NOT appear in exported JSON
      val prefsRepo = ShopPreferencesRepository(context)
      prefsRepo.saveInitialSetup("সিকিউর সবজি শপ", "হাসান", true)
      prefsRepo.setPinCode("7391")

      val dao = db.shopDao()
      val backupManager = BackupManager(context, dao, prefsRepo)
      val file = File.createTempFile("sec_backup", ".json", context.cacheDir)
      val res = backupManager.exportToUri(Uri.fromFile(file))
      assertTrue(res.isSuccess)
      val jsonText = file.readText()
      assertFalse(jsonText.contains("sha256:"))
      assertFalse(jsonText.contains("7391"))
      assertFalse(jsonText.contains("pinCode"))
      file.delete()
    }

  @Test
  fun `TC-SEC-02 manifest declares zero dangerous or internet permissions for strict offline privacy`() {
    val pkgInfo =
      context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
    val perms = pkgInfo.requestedPermissions?.toList().orEmpty()
    val androidSystemPerms = perms.filter { it.startsWith("android.permission.") }
    assertTrue(
      "Expected zero Android system/dangerous permissions, found: $androidSystemPerms",
      androidSystemPerms.isEmpty(),
    )
  }

  @Test
  fun `TC-BKP-01 restore rolls back to snapshot cleanly and rejects empty truncated or unsupported version backups without data loss`() =
    runTest {
      val dao = db.shopDao()
      val prefsRepo = ShopPreferencesRepository(context)
      prefsRepo.saveInitialSetup("আদি দোকান", "জামাল", true)
      val vegId =
        dao.insertVegetable(
          VegetableEntity(
            name = "শসা",
            category = VegetableCategory.FRUIT_VEG,
            unitType = UnitType.KG,
            stockUnits = 5_000L,
            avgCostPerUnit = 30.0,
            sellingPricePerUnit = 45.0,
          )
        )
      val partyId =
        dao.insertParty(
          PartyEntity(name = "ক্রেতা ১", phone = "01700", partyType = PartyType.CUSTOMER)
        )

      val backupManager = BackupManager(context, dao, prefsRepo)
      val snapshotFile = File.createTempFile("snapshot_v1", ".json", context.cacheDir)
      assertTrue(backupManager.exportToUri(Uri.fromFile(snapshotFile)).isSuccess)

      // Mutate live DB AFTER backup (new sale + new expense)
      dao.completeSaleAtomic(
        SaleEntity(
          customerId = partyId,
          customerName = "ক্রেতা ১",
          subtotal = 45.0,
          totalAmount = 45.0,
          paidAmount = 45.0,
        ),
        listOf(
          SaleItemEntity(
            saleId = 0L,
            vegetableId = vegId,
            vegetableName = "শসা",
            unitType = UnitType.KG,
            quantityUnits = 1_000L,
            sellingPricePerUnit = 45.0,
            costSnapshotPerUnit = 30.0,
            lineTotal = 45.0,
          )
        ),
      )
      dao.insertOrUpdateExpense(ExpenseEntity(category = ExpenseCategory.RENT, amount = 500.0))
      assertEquals(4_000L, dao.getVegetableById(vegId)!!.stockUnits)
      assertEquals(1, dao.getAllExpensesSnapshot().size)

      // Bad backups must be rejected BEFORE any DB mutation
      val badEmpty =
        File.createTempFile("bad_empty", ".json", context.cacheDir).apply { writeText("") }
      val badTruncated =
        File.createTempFile("bad_trunc", ".json", context.cacheDir).apply {
          writeText(
            """{"schemaTag":"${SobjiKhataBackupPayload.SCHEMA_TAG}","version":1,"vegetables":["""
          )
        }
      val badVersion =
        File.createTempFile("bad_ver", ".json", context.cacheDir).apply {
          writeText("""{"schemaTag":"${SobjiKhataBackupPayload.SCHEMA_TAG}","version":2}""")
        }

      assertTrue(backupManager.validateBackupUri(Uri.fromFile(badEmpty)).isFailure)
      assertTrue(backupManager.validateBackupUri(Uri.fromFile(badTruncated)).isFailure)
      assertTrue(backupManager.validateBackupUri(Uri.fromFile(badVersion)).isFailure)

      // Live DB untouched after invalid files
      assertEquals(4_000L, dao.getVegetableById(vegId)!!.stockUnits)
      assertEquals(1, dao.getAllExpensesSnapshot().size)

      // Valid restore reverts live DB back to exact snapshot (5kg stock, 0 sales, 0 expenses)
      val payload = backupManager.validateBackupUri(Uri.fromFile(snapshotFile)).getOrThrow()
      assertTrue(backupManager.restoreFromPayload(payload).isSuccess)
      assertEquals(5_000L, dao.getVegetableById(vegId)!!.stockUnits)
      assertEquals(0, dao.getAllSalesSnapshot().size)
      assertEquals(0, dao.getAllExpensesSnapshot().size)
      assertEquals("আদি দোকান", prefsRepo.preferencesFlow.first().shopName)

      snapshotFile.delete()
      badEmpty.delete()
      badTruncated.delete()
      badVersion.delete()
    }

  @Test
  fun `TC-SEC-03 manifest disables allowBackup to protect local ledger from cloud extraction`() {
    val allowBackupEnabled =
      (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP) != 0
    assertFalse("Expected android:allowBackup=\"false\"", allowBackupEnabled)
  }

  @Test
  fun `TC-DB-03 MIGRATION_1_2 upgrades existing v1 SQLite database to v2 preserving rows and creating all indexes`() =
    runTest {
      val dbFile = File(context.cacheDir, "migration_v1_test_${System.nanoTime()}.db")
      if (dbFile.exists()) dbFile.delete()

      val rawSqlite = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null)
      rawSqlite.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `vegetables` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `name` TEXT NOT NULL,
          `category` TEXT NOT NULL,
          `unitType` TEXT NOT NULL,
          `stockUnits` INTEGER NOT NULL,
          `avgCostPerUnit` REAL NOT NULL,
          `sellingPricePerUnit` REAL NOT NULL,
          `lowStockThresholdUnits` INTEGER NOT NULL,
          `isArchived` INTEGER NOT NULL,
          `createdAt` INTEGER NOT NULL
        )
        """
          .trimIndent()
      )
      rawSqlite.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `parties` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `name` TEXT NOT NULL,
          `phone` TEXT NOT NULL,
          `partyType` TEXT NOT NULL,
          `notes` TEXT NOT NULL,
          `createdAt` INTEGER NOT NULL
        )
        """
          .trimIndent()
      )
      rawSqlite.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `purchases` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `supplierId` INTEGER,
          `supplierName` TEXT NOT NULL,
          `itemsTotal` REAL NOT NULL,
          `extraCost` REAL NOT NULL,
          `paidAmount` REAL NOT NULL,
          `dueAmount` REAL NOT NULL,
          `notes` TEXT NOT NULL,
          `timestamp` INTEGER NOT NULL,
          `isCancelled` INTEGER NOT NULL
        )
        """
          .trimIndent()
      )
      rawSqlite.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `purchase_items` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `purchaseId` INTEGER NOT NULL,
          `vegetableId` INTEGER NOT NULL,
          `vegetableName` TEXT NOT NULL,
          `unitType` TEXT NOT NULL,
          `quantityUnits` INTEGER NOT NULL,
          `unitCost` REAL NOT NULL,
          `lineTotal` REAL NOT NULL
        )
        """
          .trimIndent()
      )
      rawSqlite.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `sales` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `customerId` INTEGER,
          `customerName` TEXT NOT NULL,
          `subtotal` REAL NOT NULL,
          `discount` REAL NOT NULL,
          `totalAmount` REAL NOT NULL,
          `paidAmount` REAL NOT NULL,
          `dueAmount` REAL NOT NULL,
          `paymentMethod` TEXT NOT NULL,
          `notes` TEXT NOT NULL,
          `timestamp` INTEGER NOT NULL,
          `isCancelled` INTEGER NOT NULL
        )
        """
          .trimIndent()
      )
      rawSqlite.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `sale_items` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `saleId` INTEGER NOT NULL,
          `vegetableId` INTEGER NOT NULL,
          `vegetableName` TEXT NOT NULL,
          `unitType` TEXT NOT NULL,
          `quantityUnits` INTEGER NOT NULL,
          `sellingPricePerUnit` REAL NOT NULL,
          `costSnapshotPerUnit` REAL NOT NULL,
          `lineTotal` REAL NOT NULL
        )
        """
          .trimIndent()
      )
      rawSqlite.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `wastages` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `vegetableId` INTEGER NOT NULL,
          `vegetableName` TEXT NOT NULL,
          `unitType` TEXT NOT NULL,
          `quantityUnits` INTEGER NOT NULL,
          `unitCostSnapshot` REAL NOT NULL,
          `lossAmount` REAL NOT NULL,
          `reason` TEXT NOT NULL,
          `notes` TEXT NOT NULL,
          `timestamp` INTEGER NOT NULL,
          `isCancelled` INTEGER NOT NULL
        )
        """
          .trimIndent()
      )
      rawSqlite.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `expenses` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `category` TEXT NOT NULL,
          `amount` REAL NOT NULL,
          `paymentMethod` TEXT NOT NULL,
          `note` TEXT NOT NULL,
          `timestamp` INTEGER NOT NULL,
          `isCancelled` INTEGER NOT NULL
        )
        """
          .trimIndent()
      )
      rawSqlite.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `due_payments` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `partyId` INTEGER NOT NULL,
          `partyName` TEXT NOT NULL,
          `partyType` TEXT NOT NULL,
          `amount` REAL NOT NULL,
          `paymentMethod` TEXT NOT NULL,
          `note` TEXT NOT NULL,
          `timestamp` INTEGER NOT NULL,
          `isCancelled` INTEGER NOT NULL
        )
        """
          .trimIndent()
      )
      rawSqlite.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `inventory_movements` (
          `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          `vegetableId` INTEGER NOT NULL,
          `vegetableName` TEXT NOT NULL,
          `movementType` TEXT NOT NULL,
          `deltaUnits` INTEGER NOT NULL,
          `previousStock` INTEGER NOT NULL,
          `newStock` INTEGER NOT NULL,
          `reason` TEXT NOT NULL,
          `referenceId` INTEGER,
          `timestamp` INTEGER NOT NULL
        )
        """
          .trimIndent()
      )
      rawSqlite.execSQL(
        """
        INSERT INTO vegetables (id, name, category, unitType, stockUnits, avgCostPerUnit, sellingPricePerUnit, lowStockThresholdUnits, isArchived, createdAt)
        VALUES (1, 'আলু', 'ROOT', 'KG', 5000, 30.0, 42.0, 2000, 0, 1700000000000)
        """
          .trimIndent()
      )
      rawSqlite.version = 1
      rawSqlite.close()

      val migratedDb =
        Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
          .addMigrations(AppDatabase.MIGRATION_1_2)
          .allowMainThreadQueries()
          .build()

      try {
        val vegs = migratedDb.shopDao().getAllVegetablesSnapshot()
        assertEquals(1, vegs.size)
        assertEquals("আলু", vegs.first().name)
        assertEquals(5_000L, vegs.first().stockUnits)

        val cursor =
          migratedDb.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'index_%'"
          )
        val indexNames = mutableListOf<String>()
        cursor.use {
          while (it.moveToNext()) {
            indexNames.add(it.getString(0))
          }
        }
        assertTrue(indexNames.size >= 17)
        assertTrue(indexNames.contains("index_sales_timestamp"))
        assertTrue(indexNames.contains("index_due_payments_partyId_partyType_isCancelled"))
      } finally {
        migratedDb.close()
        dbFile.delete()
      }
    }

  @Test
  fun `TC-BKP-02 BackupManager rejects duplicate entity IDs and orphan saleItems or purchaseItems`() =
    runTest {
      val dao = db.shopDao()
      val prefsRepo = ShopPreferencesRepository(context)
      val backupManager = BackupManager(context, dao, prefsRepo)

      val dupVegFile =
        File.createTempFile("bad_dup_veg", ".json", context.cacheDir).apply {
          writeText(
            """{"schemaTag":"${SobjiKhataBackupPayload.SCHEMA_TAG}","version":1,"exportedAt":1700000000000,"vegetables":[{"id":1,"name":"আলু","category":"ROOT","unitType":"KG","stockUnits":0,"avgCostPerUnit":10.0,"sellingPricePerUnit":20.0},{"id":1,"name":"পেঁয়াজ","category":"ROOT","unitType":"KG","stockUnits":0,"avgCostPerUnit":20.0,"sellingPricePerUnit":30.0}]}"""
          )
        }
      val orphanSaleItemFile =
        File.createTempFile("bad_orphan_sale", ".json", context.cacheDir).apply {
          writeText(
            """{"schemaTag":"${SobjiKhataBackupPayload.SCHEMA_TAG}","version":1,"exportedAt":1700000000000,"sales":[],"saleItems":[{"id":1,"saleId":999,"vegetableId":1,"vegetableName":"আলু","unitType":"KG","quantityUnits":1000,"sellingPricePerUnit":40.0,"costSnapshotPerUnit":30.0,"lineTotal":40.0}]}"""
          )
        }
      val orphanPurchaseItemFile =
        File.createTempFile("bad_orphan_purchase", ".json", context.cacheDir).apply {
          writeText(
            """{"schemaTag":"${SobjiKhataBackupPayload.SCHEMA_TAG}","version":1,"exportedAt":1700000000000,"purchases":[],"purchaseItems":[{"id":1,"purchaseId":888,"vegetableId":1,"vegetableName":"আলু","unitType":"KG","quantityUnits":1000,"unitCost":30.0,"lineTotal":30.0}]}"""
          )
        }

      assertTrue(backupManager.validateBackupUri(Uri.fromFile(dupVegFile)).isFailure)
      assertTrue(backupManager.validateBackupUri(Uri.fromFile(orphanSaleItemFile)).isFailure)
      assertTrue(backupManager.validateBackupUri(Uri.fromFile(orphanPurchaseItemFile)).isFailure)

      dupVegFile.delete()
      orphanSaleItemFile.delete()
      orphanPurchaseItemFile.delete()
    }
}
