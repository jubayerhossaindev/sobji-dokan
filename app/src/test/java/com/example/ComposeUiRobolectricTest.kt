package com.example

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import com.example.data.local.DuePaymentEntity
import com.example.data.local.ExpenseCategory
import com.example.data.local.ExpenseEntity
import com.example.data.local.InventoryMovementEntity
import com.example.data.local.MovementType
import com.example.data.local.PartyEntity
import com.example.data.local.PartyType
import com.example.data.local.PaymentMethod
import com.example.data.local.PurchaseEntity
import com.example.data.local.SaleEntity
import com.example.data.local.UnitType
import com.example.data.local.VegetableCategory
import com.example.data.local.VegetableEntity
import com.example.data.local.WastageEntity
import com.example.data.local.WastageReason
import com.example.data.prefs.AppThemeMode
import com.example.data.prefs.ShopPreferences
import com.example.domain.FinancialSummary
import com.example.ui.PartyBalanceSummary
import com.example.ui.PosCartItem
import com.example.ui.ShopUiState
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.ExpensesAndReportsScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.PartiesLedgerScreen
import com.example.ui.screens.PinLockScreen
import com.example.ui.screens.QuickPosScreen
import com.example.ui.screens.SalesHistoryScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StockAndPurchaseScreen
import com.example.ui.theme.SobjiKhataTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class ComposeUiRobolectricTest {

  @get:Rule val composeRule = createComposeRule()

  @Test
  fun `onboarding validates blank shop name and submits setup`() {
    var submittedShop = ""
    var submittedOwner = ""
    var submittedBengali = true
    composeRule.setContent {
      SobjiKhataTheme {
        OnboardingScreen(
          isSaving = false,
          onCompleteSetup = { shop, owner, bn, _ ->
            submittedShop = shop
            submittedOwner = owner
            submittedBengali = bn
          },
        )
      }
    }
    composeRule.waitForIdle()

    // Blank name submission shows validation message
    composeRule.onNodeWithTag("start_shop_button").performScrollTo().performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("অনুগ্রহ করে দোকানের নাম লিখুন।").assertIsDisplayed()

    composeRule
      .onNodeWithTag("setup_shop_name_input")
      .performScrollTo()
      .performTextInput("সবুজ বাজার")
    composeRule.onNodeWithTag("setup_owner_name_input").performScrollTo().performTextInput("জামাল")
    composeRule.onNodeWithTag("chip_en_digits").performScrollTo().performClick()
    composeRule.onNodeWithTag("start_shop_button").performScrollTo().performClick()
    composeRule.waitForIdle()

    assertEquals("সবুজ বাজার", submittedShop)
    assertEquals("জামাল", submittedOwner)
    assertEquals(false, submittedBengali)
  }

  @Test
  fun `pin lock screen rejects wrong pin and unlocks on valid pin`() {
    composeRule.setContent {
      SobjiKhataTheme {
        PinLockScreen(shopName = "মা সবজি ভান্ডার", onTryUnlock = { it == "2580" })
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("pin_unlock_input").performTextInput("1111")
    composeRule.onNodeWithTag("pin_unlock_button").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("PIN সঠিক নয়, আবার চেষ্টা করুন").assertIsDisplayed()

    composeRule.onNodeWithTag("pin_unlock_input").performTextInput("2580")
    composeRule.onNodeWithTag("pin_unlock_button").performClick()
    composeRule.waitForIdle()
  }

  @Test
  fun `dashboard displays net profit hero card and invokes quick actions`() {
    var quickSaleClicked = false
    var settingsClicked = false
    val state =
      ShopUiState(
        isLoaded = true,
        preferences = ShopPreferences(shopName = "বিসমিল্লাহ সবজি ঘর", isSetupComplete = true),
        todaySummary =
          FinancialSummary(
            netProfit = 1250.0,
            netSales = 4000.0,
            cogs = 2500.0,
            operatingExpenses = 250.0,
          ),
        activeVegetables =
          listOf(
            VegetableEntity(
              id = 1L,
              name = "আলু",
              category = VegetableCategory.ROOT,
              unitType = UnitType.KG,
              stockUnits = 1_000L,
              lowStockThresholdUnits = 5_000L,
              avgCostPerUnit = 30.0,
              sellingPricePerUnit = 40.0,
            )
          ),
        lowStockVegetables =
          listOf(
            VegetableEntity(
              id = 1L,
              name = "আলু",
              category = VegetableCategory.ROOT,
              unitType = UnitType.KG,
              stockUnits = 1_000L,
              lowStockThresholdUnits = 5_000L,
              avgCostPerUnit = 30.0,
              sellingPricePerUnit = 40.0,
            )
          ),
      )

    composeRule.setContent {
      SobjiKhataTheme {
        DashboardScreen(
          uiState = state,
          onOpenSettings = { settingsClicked = true },
          onQuickSale = { quickSaleClicked = true },
          onQuickPurchase = {},
          onQuickWastage = {},
          onQuickExpense = {},
          onOpenStock = {},
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("hero_net_profit_card").assertIsDisplayed()
    composeRule.onNodeWithText("বিসমিল্লাহ সবজি ঘর").assertIsDisplayed()
    composeRule.onNodeWithTag("dashboard_list").performScrollToNode(hasTestTag("quick_action_sale"))
    composeRule.onNodeWithTag("quick_action_sale").performClick()
    composeRule.onNodeWithTag("dashboard_settings_button").performClick()
    assertTrue(quickSaleClicked)
    assertTrue(settingsClicked)
  }

  @Test
  fun `quick pos warns on zero stock and completes sale after confirmation`() {
    val tomato =
      VegetableEntity(
        id = 1L,
        name = "টমেটো",
        category = VegetableCategory.FRUIT_VEG,
        unitType = UnitType.KG,
        stockUnits = 0L,
        avgCostPerUnit = 40.0,
        sellingPricePerUnit = 60.0,
      )
    var saleCompleted = false
    var currentState by
      mutableStateOf(
        ShopUiState(
          isLoaded = true,
          preferences = ShopPreferences(shopName = "টেস্ট", isSetupComplete = true),
          activeVegetables = listOf(tomato),
        )
      )

    composeRule.setContent {
      SobjiKhataTheme {
        QuickPosScreen(
          uiState = currentState,
          onOpenHistory = {},
          onOpenStock = {},
          onAddToCart = { veg, qty, price ->
            currentState = currentState.copy(posCart = listOf(PosCartItem(veg, qty, price, 60.0)))
          },
          onRemoveFromCart = {},
          onClearCart = {},
          onCompleteSale = { _, _, _, _, _, _, onDone ->
            saleCompleted = true
            onDone()
          },
          onCreateCustomer = { name, phone ->
            PartyEntity(id = 1L, name = name, phone = phone, partyType = PartyType.CUSTOMER)
          },
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("pos_veg_card_1").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("পর্যাপ্ত স্টক নেই (চাইলে তবুও যোগ করতে পারবেন)").assertIsDisplayed()
    composeRule.onNodeWithTag("qty_confirm_button").performScrollTo().performClick()
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeBy(600)
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("pos_sticky_cart_bar").assertIsDisplayed()
    composeRule.onNodeWithTag("pos_quick_cash_save_button").performClick()
    composeRule.waitForIdle()

    // Confirmation dialog appears because tomato stock was 0
    composeRule.onNodeWithText("স্টক সতর্কতা").assertIsDisplayed()
    composeRule.onNodeWithText("হ্যাঁ, বিক্রি করুন").performClick()
    composeRule.waitForIdle()
    assertTrue(saleCompleted)
  }

  @Test
  fun `stock screen shows wastage and movement log tab with cancel confirmation`() {
    var cancelledWastageId = -1L
    val state =
      ShopUiState(
        isLoaded = true,
        preferences = ShopPreferences(isSetupComplete = true),
        wastages =
          listOf(
            WastageEntity(
              id = 7L,
              vegetableId = 1L,
              vegetableName = "শসা",
              unitType = UnitType.KG,
              quantityUnits = 500L,
              unitCostSnapshot = 40.0,
              lossAmount = 20.0,
              reason = WastageReason.ROTTEN,
            )
          ),
        movements =
          listOf(
            InventoryMovementEntity(
              id = 1L,
              vegetableId = 1L,
              vegetableName = "শসা",
              movementType = MovementType.WASTAGE,
              deltaUnits = -500L,
              previousStock = 2000L,
              newStock = 1500L,
              reason = "পচে যাওয়া",
            )
          ),
      )

    composeRule.setContent {
      SobjiKhataTheme {
        StockAndPurchaseScreen(
          uiState = state,
          initialTab = 2,
          onOpenNewPurchase = {},
          onSaveVegetable = { _, _, _, _, _, _, _, _, _ -> },
          onToggleArchive = {},
          onAdjustStock = { _, _, _ -> },
          onRecordWastage = { _, _, _, _ -> },
          onCancelWastage = { cancelledWastageId = it },
          onCancelPurchase = {},
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("wastage_card_7").assertIsDisplayed()
    composeRule.onNodeWithTag("cancel_wastage_7").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("অপচয় বাতিল করবেন?").assertIsDisplayed()
    composeRule.onNodeWithText("হ্যাঁ, বাতিল করুন").performClick()
    composeRule.waitForIdle()
    assertEquals(7L, cancelledWastageId)
  }

  @Test
  fun `reports and credit ledger tabs adapt to optional credit ledger setting`() {
    var showCredit by mutableStateOf(true)
    val state =
      ShopUiState(
        isLoaded = true,
        expenses = listOf(ExpenseEntity(id = 1L, category = ExpenseCategory.RENT, amount = 500.0)),
        todaySummary = FinancialSummary(netProfit = 900.0),
      )

    composeRule.setContent {
      SobjiKhataTheme {
        ExpensesAndReportsScreen(
          uiState = state.copy(preferences = ShopPreferences(showCreditLedger = showCredit)),
          initialTab = 1,
          onSaveExpense = { _, _, _, _, _, _ -> },
          onCancelExpense = {},
          onAddParty = { _, _, _, _ -> },
          onRecordDuePayment = { _, _, _, _ -> },
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("pnl_waterfall_card").assertIsDisplayed()
    composeRule.onNodeWithTag("tab_credit_ledger").assertIsDisplayed()
    composeRule.onNodeWithTag("tab_expenses").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("expense_card_1").assertIsDisplayed()
    composeRule.onAllNodesWithText("দোকান ভাড়া").onLast().assertIsDisplayed()

    showCredit = false
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("tab_credit_ledger").assertDoesNotExist()
  }

  @Test
  fun `settings screen renders toggles pin and backup actions`() {
    var toggledBengali = true
    composeRule.setContent {
      SobjiKhataTheme {
        SettingsScreen(
          uiState =
            ShopUiState(
              preferences = ShopPreferences(shopName = "তাজা সবজি", useBengaliDigits = true)
            ),
          pendingRestore = null,
          onBack = {},
          onUpdateShopProfile = { _, _ -> },
          onToggleBengaliDigits = { toggledBengali = it },
          onToggleCreditLedger = {},
          onSetPinCode = {},
          onExportBackupUri = {},
          onSelectRestoreUri = {},
          onConfirmRestore = {},
          onDismissRestore = {},
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("switch_bengali_digits").assertIsDisplayed().performClick()
    composeRule.waitForIdle()
    assertEquals(false, toggledBengali)
    composeRule
      .onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("export_backup_button"))
    composeRule.onNodeWithTag("export_backup_button").assertIsDisplayed()
    composeRule
      .onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("restore_backup_button"))
    composeRule.onNodeWithTag("restore_backup_button").assertIsDisplayed()
  }

  @Test
  fun `expense delete requires confirmation dialog before cancelling`() {
    var cancelledId = -1L
    val state =
      ShopUiState(
        isLoaded = true,
        expenses =
          listOf(ExpenseEntity(id = 12L, category = ExpenseCategory.TRANSPORT, amount = 180.0)),
      )

    composeRule.setContent {
      SobjiKhataTheme {
        ExpensesAndReportsScreen(
          uiState = state,
          initialTab = 0,
          onSaveExpense = { _, _, _, _, _, _ -> },
          onCancelExpense = { cancelledId = it },
          onAddParty = { _, _, _, _ -> },
          onRecordDuePayment = { _, _, _, _ -> },
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("expense_delete_12").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("খরচ বাতিল করবেন?").assertIsDisplayed()
    assertEquals(-1L, cancelledId)

    composeRule.onNodeWithTag("confirm_dialog_yes").performClick()
    composeRule.waitForIdle()
    assertEquals(12L, cancelledId)
  }

  @Test
  fun `parties ledger shows summary strip opens party detail sheet and starts sale`() {
    val customer =
      PartyEntity(id = 4L, name = "করিম ভাই", phone = "01711", partyType = PartyType.CUSTOMER)
    val supplier =
      PartyEntity(id = 8L, name = "বড় আড়ত", phone = "01822", partyType = PartyType.SUPPLIER)
    var startedCustomerId = -1L
    val state =
      ShopUiState(
        isLoaded = true,
        preferences =
          ShopPreferences(
            isSetupComplete = true,
            showCreditLedger = true,
            useBengaliDigits = false,
          ),
        customerBalances =
          listOf(
            PartyBalanceSummary(
              customer,
              totalBilled = 500.0,
              totalPaid = 300.0,
              balanceDue = 200.0,
            )
          ),
        supplierBalances =
          listOf(
            PartyBalanceSummary(
              supplier,
              totalBilled = 1000.0,
              totalPaid = 400.0,
              balanceDue = 600.0,
            )
          ),
        sales =
          listOf(
            SaleEntity(
              id = 21L,
              customerId = 4L,
              customerName = "করিম ভাই",
              subtotal = 500.0,
              totalAmount = 500.0,
              paidAmount = 300.0,
              dueAmount = 200.0,
              paymentMethod = PaymentMethod.CREDIT,
            )
          ),
        purchases =
          listOf(
            PurchaseEntity(
              id = 31L,
              supplierId = 8L,
              supplierName = "বড় আড়ত",
              itemsTotal = 1000.0,
              paidAmount = 400.0,
              dueAmount = 600.0,
            )
          ),
        duePayments =
          listOf(
            DuePaymentEntity(
              id = 1L,
              partyId = 4L,
              partyType = PartyType.CUSTOMER,
              partyName = "করিম ভাই",
              amount = 100.0,
              paymentMethod = PaymentMethod.CASH,
            )
          ),
      )

    composeRule.setContent {
      SobjiKhataTheme {
        PartiesLedgerScreen(
          uiState = state,
          initialSubTab = 0,
          onAddParty = { _, _, _, _ -> },
          onRecordDuePayment = { _, _, _, _, onDone -> onDone() },
          onStartSaleForCustomer = { startedCustomerId = it },
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("parties_subtab_customers").assertIsDisplayed()
    composeRule.onNodeWithTag("party_card_4").performClick()
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("detail_party_pay_button").assertIsDisplayed()
    composeRule.onNodeWithTag("party_detail_tab_payments").performClick()
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("detail_party_new_sale_button").performClick()
    composeRule.waitForIdle()
    assertEquals(4L, startedCustomerId)
  }

  @Test
  fun `checkout preserves manual paid amount when discount changes and lets customer be attached to cash sale`() {
    val onion =
      VegetableEntity(
        id = 2L,
        name = "পেঁয়াজ",
        category = VegetableCategory.ROOT,
        unitType = UnitType.KG,
        stockUnits = 10_000L,
        avgCostPerUnit = 60.0,
        sellingPricePerUnit = 100.0,
      )
    val customer =
      PartyEntity(id = 9L, name = "রহিম মিয়া", phone = "019", partyType = PartyType.CUSTOMER)
    var savedCustomerId: Long? = null
    var savedPaid = -1.0
    val state =
      ShopUiState(
        isLoaded = true,
        preferences =
          ShopPreferences(
            isSetupComplete = true,
            showCreditLedger = true,
            useBengaliDigits = false,
          ),
        activeVegetables = listOf(onion),
        posCart = listOf(PosCartItem(onion, 1_000L, 100.0, 100.0)),
        customerBalances = listOf(PartyBalanceSummary(customer, 0.0, 0.0, 0.0)),
      )

    composeRule.setContent {
      SobjiKhataTheme {
        QuickPosScreen(
          uiState = state,
          onOpenHistory = {},
          onOpenStock = {},
          onAddToCart = { _, _, _ -> },
          onRemoveFromCart = {},
          onClearCart = {},
          onCompleteSale = { custId, _, _, paid, _, _, onDone ->
            savedCustomerId = custId
            savedPaid = paid
            onDone()
          },
          onCreateCustomer = { n, p -> PartyEntity(99L, n, p, PartyType.CUSTOMER) },
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("pos_cart_details_button").performClick()
    composeRule.waitForIdle()

    // Select repeat customer even when paying in full/partial cash
    composeRule.onNodeWithTag("checkout_customer_selector").performScrollTo().performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("picker_party_9").performClick()
    composeRule.waitForIdle()

    // Manually enter paid = 50, then apply discount = 10; paid must stay 50 (not reset to 90!)
    composeRule.onNodeWithTag("checkout_paid_input").performScrollTo().performTextClearance()
    composeRule.onNodeWithTag("checkout_paid_input").performTextInput("50")
    composeRule.onNodeWithTag("checkout_discount_input").performScrollTo().performTextInput("10")
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("checkout_paid_input").assertTextContains("50")

    composeRule.onNodeWithTag("checkout_complete_button").performScrollTo().performClick()
    composeRule.waitForIdle()
    assertEquals(9L, savedCustomerId)
    assertEquals(50.0, savedPaid, 0.001)
  }

  @Test
  fun `stock movement log formats real unit instead of raw akok`() {
    val cucumber =
      VegetableEntity(
        id = 1L,
        name = "শসা",
        category = VegetableCategory.FRUIT_VEG,
        unitType = UnitType.KG,
        stockUnits = 1_500L,
        avgCostPerUnit = 40.0,
        sellingPricePerUnit = 60.0,
      )
    val state =
      ShopUiState(
        isLoaded = true,
        allVegetables = listOf(cucumber),
        activeVegetables = listOf(cucumber),
        movements =
          listOf(
            InventoryMovementEntity(
              id = 5L,
              vegetableId = 1L,
              vegetableName = "শসা",
              movementType = MovementType.WASTAGE,
              deltaUnits = -500L,
              previousStock = 2_000L,
              newStock = 1_500L,
              reason = "পচে যাওয়া",
            )
          ),
      )

    composeRule.setContent {
      SobjiKhataTheme {
        StockAndPurchaseScreen(
          uiState = state,
          initialTab = 2,
          onOpenNewPurchase = {},
          onSaveVegetable = { _, _, _, _, _, _, _, _, _ -> },
          onToggleArchive = {},
          onAdjustStock = { _, _, _ -> },
          onRecordWastage = { _, _, _, _ -> },
          onCancelPurchase = {},
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithText("-৫০০ গ্রাম").assertIsDisplayed()
  }

  @Test
  fun `settings theme selector changes theme mode and pin disable shows confirmation`() {
    var selectedTheme = AppThemeMode.SYSTEM
    var pinSetTo: String? = null
    composeRule.setContent {
      SobjiKhataTheme {
        SettingsScreen(
          uiState =
            ShopUiState(
              preferences =
                ShopPreferences(
                  shopName = "তাজা বাজার",
                  pinCode = "hash",
                  themeMode = AppThemeMode.SYSTEM,
                )
            ),
          pendingRestore = null,
          onBack = {},
          onUpdateShopProfile = { _, _ -> },
          onToggleBengaliDigits = {},
          onToggleCreditLedger = {},
          onSetPinCode = { pinSetTo = it },
          onExportBackupUri = {},
          onSelectRestoreUri = {},
          onConfirmRestore = {},
          onDismissRestore = {},
          onSetThemeMode = { selectedTheme = it },
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("theme_chip_DARK"))
    composeRule.onNodeWithTag("theme_chip_DARK").performClick()
    composeRule.waitForIdle()
    assertEquals(AppThemeMode.DARK, selectedTheme)

    composeRule.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("disable_pin_button"))
    composeRule.onNodeWithTag("disable_pin_button").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("PIN লক বন্ধ করবেন?").assertIsDisplayed()
    composeRule.onNodeWithTag("confirm_dialog_yes").performClick()
    composeRule.waitForIdle()
    assertEquals("", pinSetTo)

    composeRule
      .onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("settings_about_card"))
    composeRule.onNodeWithTag("settings_about_card").assertIsDisplayed()
  }

  @Test
  fun `dark theme and 150 percent font scale render dashboard and sales history cleanly`() {
    val state =
      ShopUiState(
        isLoaded = true,
        preferences =
          ShopPreferences(
            shopName = "রহমান সবজি ঘর",
            isSetupComplete = true,
            showCreditLedger = true,
          ),
        todaySummary =
          FinancialSummary(netProfit = 1450.0, netSales = 5200.0, activeSalesCount = 6),
        sales =
          listOf(
            SaleEntity(
              id = 1L,
              customerName = "সাধারণ ক্রেতা",
              subtotal = 120.0,
              totalAmount = 120.0,
              paidAmount = 120.0,
              dueAmount = 0.0,
              paymentMethod = PaymentMethod.CASH,
            )
          ),
      )

    composeRule.setContent {
      CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.5f)) {
        SobjiKhataTheme(darkTheme = true) {
          SalesHistoryScreen(uiState = state, onBack = {}, onCancelSale = {})
        }
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("sales_summary_banner").assertIsDisplayed()
    composeRule.onNodeWithTag("sale_card_1").assertIsDisplayed()
  }

  @Test
  fun `quick pos taka mode computes grams from taka and shows existing cart badge`() {
    val potato =
      VegetableEntity(
        id = 1L,
        name = "আলু",
        category = VegetableCategory.ROOT,
        unitType = UnitType.KG,
        stockUnits = 10_000L,
        avgCostPerUnit = 40.0,
        sellingPricePerUnit = 60.0,
      )
    var addedQty = -1L
    var addedPrice = -1.0
    var state by
      mutableStateOf(
        ShopUiState(
          isLoaded = true,
          preferences =
            ShopPreferences(shopName = "টেস্ট", isSetupComplete = true, useBengaliDigits = true),
          activeVegetables = listOf(potato),
        )
      )

    composeRule.setContent {
      SobjiKhataTheme {
        QuickPosScreen(
          uiState = state,
          onOpenHistory = {},
          onOpenStock = {},
          onAddToCart = { veg, qty, price ->
            addedQty = qty
            addedPrice = price
            state = state.copy(posCart = listOf(PosCartItem(veg, qty, price, 30.0)))
          },
          onRemoveFromCart = {},
          onClearCart = {},
          onCompleteSale = { _, _, _, _, _, _, _ -> },
          onCreateCustomer = { n, p -> PartyEntity(1L, n, p, PartyType.CUSTOMER) },
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("pos_veg_card_1").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("pos_mode_taka_chip").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("pos_taka_input").performTextClearance()
    composeRule.onNodeWithTag("pos_taka_input").performTextInput("30")
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("pos_taka_weight_preview").assertIsDisplayed()
    composeRule
      .onNodeWithTag("pos_taka_weight_preview")
      .assertTextContains("৫০০ গ্রাম", substring = true)
    composeRule.onNodeWithTag("qty_confirm_button").performScrollTo().performClick()
    composeRule.waitForIdle()

    assertEquals(500L, addedQty)
    assertEquals(60.0, addedPrice, 0.001)

    // Re-open same item now shows existing in-cart badge
    composeRule.onNodeWithTag("pos_veg_card_1").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("pos_in_cart_badge").assertIsDisplayed()
  }

  @Test
  fun `stock screen bulk price update sheet edits and saves prices`() {
    val potato =
      VegetableEntity(
        id = 1L,
        name = "আলু",
        category = VegetableCategory.ROOT,
        unitType = UnitType.KG,
        stockUnits = 5_000L,
        avgCostPerUnit = 30.0,
        sellingPricePerUnit = 40.0,
      )
    var savedMap: Map<Long, Double> = emptyMap()
    val state =
      ShopUiState(
        isLoaded = true,
        preferences = ShopPreferences(isSetupComplete = true, useBengaliDigits = false),
        activeVegetables = listOf(potato),
        allVegetables = listOf(potato),
      )

    composeRule.setContent {
      SobjiKhataTheme {
        StockAndPurchaseScreen(
          uiState = state,
          initialTab = 0,
          onOpenNewPurchase = {},
          onSaveVegetable = { _, _, _, _, _, _, _, _, _ -> },
          onToggleArchive = {},
          onAdjustStock = { _, _, _ -> },
          onRecordWastage = { _, _, _, _ -> },
          onCancelPurchase = {},
          onBulkUpdatePrices = { map, onDone ->
            savedMap = map
            onDone()
          },
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("bulk_price_update_button").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("bulk_price_input_1").performTextClearance()
    composeRule.onNodeWithTag("bulk_price_input_1").performTextInput("55")
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("bulk_price_save_button").performClick()
    composeRule.waitForIdle()

    assertEquals(55.0, savedMap[1L] ?: -1.0, 0.001)
  }

  @Test
  fun `parties ledger shows due share button and cancels due payment after confirmation`() {
    val customer =
      PartyEntity(id = 4L, name = "করিম ভাই", phone = "01711", partyType = PartyType.CUSTOMER)
    var cancelledPaymentId = -1L
    val state =
      ShopUiState(
        isLoaded = true,
        preferences =
          ShopPreferences(shopName = "মা সবজি", isSetupComplete = true, showCreditLedger = true),
        customerBalances =
          listOf(
            PartyBalanceSummary(
              customer,
              totalBilled = 500.0,
              totalPaid = 300.0,
              balanceDue = 200.0,
            )
          ),
        duePayments =
          listOf(
            DuePaymentEntity(
              id = 15L,
              partyId = 4L,
              partyType = PartyType.CUSTOMER,
              partyName = "করিম ভাই",
              amount = 100.0,
              paymentMethod = PaymentMethod.CASH,
            )
          ),
      )

    composeRule.setContent {
      SobjiKhataTheme {
        PartiesLedgerScreen(
          uiState = state,
          initialSubTab = 0,
          onAddParty = { _, _, _, _ -> },
          onRecordDuePayment = { _, _, _, _, onDone -> onDone() },
          onCancelDuePayment = { cancelledPaymentId = it },
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("party_card_4").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("detail_party_share_due_button").assertIsDisplayed()

    composeRule.onNodeWithTag("party_detail_tab_payments").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("cancel_due_payment_15").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("বকেয়া লেনদেন বাতিল করবেন?").assertIsDisplayed()
    composeRule.onNodeWithTag("confirm_dialog_yes").performClick()
    composeRule.waitForIdle()

    assertEquals(15L, cancelledPaymentId)
  }

  @Test
  fun `dashboard displays backup reminder when 5 sales exist without recent backup`() {
    val fiveSales =
      (1L..5L).map { idx ->
        SaleEntity(id = idx, subtotal = 100.0, totalAmount = 100.0, paidAmount = 100.0)
      }
    var openedSettingsFromReminder = false
    val dashState =
      ShopUiState(
        isLoaded = true,
        preferences =
          ShopPreferences(shopName = "তাজা শপ", isSetupComplete = true, lastBackupTimestamp = 0L),
        sales = fiveSales,
      )

    composeRule.setContent {
      SobjiKhataTheme {
        DashboardScreen(
          uiState = dashState,
          onOpenSettings = { openedSettingsFromReminder = true },
          onQuickSale = {},
          onQuickPurchase = {},
          onQuickWastage = {},
          onQuickExpense = {},
          onOpenStock = {},
        )
      }
    }
    composeRule.waitForIdle()
    composeRule
      .onNodeWithTag("dashboard_list")
      .performScrollToNode(hasTestTag("dashboard_backup_reminder_card"))
    composeRule.onNodeWithTag("dashboard_backup_reminder_card").assertIsDisplayed()
    composeRule.onNodeWithTag("dashboard_backup_now_button").performClick()
    assertTrue(openedSettingsFromReminder)
  }

  @Test
  fun `reports screen displays end of day cash drawer reconciliation card`() {
    val reportState =
      ShopUiState(
        isLoaded = true,
        preferences = ShopPreferences(shopName = "তাজা শপ", isSetupComplete = true),
        todaySummary =
          FinancialSummary(
            netSales = 500.0,
            cashSalesAndCollections = 400.0,
            mobileSalesAndCollections = 100.0,
            cashOutflowOnly = 150.0,
            expectedCashInDrawer = 250.0,
          ),
      )

    composeRule.setContent {
      SobjiKhataTheme {
        ExpensesAndReportsScreen(
          uiState = reportState,
          initialTab = 1,
          onSaveExpense = { _, _, _, _, _, _ -> },
          onCancelExpense = {},
          onAddParty = { _, _, _, _ -> },
          onRecordDuePayment = { _, _, _, _ -> },
        )
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("eod_cash_reconcile_card").assertIsDisplayed()
  }

  @Test
  fun `pin lock screen displays lockout banner during cooldown`() {
    composeRule.setContent {
      SobjiKhataTheme {
        PinLockScreen(
          shopName = "তাজা শপ",
          lockoutUntilMs = System.currentTimeMillis() + 25_000L,
          onTryUnlock = { false },
        )
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("pin_lockout_banner").assertIsDisplayed()
  }

  @Test
  fun `settings pin dialog shows offline recovery warning`() {
    composeRule.setContent {
      SobjiKhataTheme {
        SettingsScreen(
          uiState = ShopUiState(preferences = ShopPreferences(shopName = "তাজা শপ")),
          pendingRestore = null,
          onBack = {},
          onUpdateShopProfile = { _, _ -> },
          onToggleBengaliDigits = {},
          onToggleCreditLedger = {},
          onSetPinCode = {},
          onExportBackupUri = {},
          onSelectRestoreUri = {},
          onConfirmRestore = {},
          onDismissRestore = {},
        )
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("setup_pin_button"))
    composeRule.onNodeWithTag("setup_pin_button").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("pin_recovery_warning").assertIsDisplayed()
  }
}
