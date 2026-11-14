package com.example.qa

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import com.example.data.local.ExpenseCategory
import com.example.data.local.ExpenseEntity
import com.example.data.local.PartyEntity
import com.example.data.local.PartyType
import com.example.data.local.UnitType
import com.example.data.local.VegetableCategory
import com.example.data.local.VegetableEntity
import com.example.data.prefs.ShopPreferences
import com.example.ui.PosCartItem
import com.example.ui.PurchaseDraftItem
import com.example.ui.ShopUiState
import com.example.ui.screens.AddEditExpenseSheet
import com.example.ui.screens.NewPurchaseScreen
import com.example.ui.screens.QuickPosScreen
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
class EndToEndAndUiQaTest {

  @get:Rule val composeRule = createComposeRule()

  @Test
  fun `TC-UI-01 BUG-001 fix clearing purchase paid input defaults to zero credit instead of full bill`() {
    val veg =
      VegetableEntity(
        id = 1L,
        name = "আলু",
        category = VegetableCategory.ROOT,
        unitType = UnitType.KG,
        stockUnits = 5_000L,
        avgCostPerUnit = 30.0,
        sellingPricePerUnit = 40.0,
      )
    val supplier =
      PartyEntity(id = 7L, name = "রশিদ আড়ত", phone = "017", partyType = PartyType.SUPPLIER)
    var savedPaid = -1.0
    var savedDue = -1.0
    val uiState =
      ShopUiState(
        isLoaded = true,
        preferences =
          ShopPreferences(
            isSetupComplete = true,
            showCreditLedger = true,
            useBengaliDigits = false,
          ),
        activeVegetables = listOf(veg),
        purchaseCart = listOf(PurchaseDraftItem(veg, 10_000L, 30.0, 300.0)),
        supplierBalances =
          listOf(
            com.example.ui.PartyBalanceSummary(
              supplier,
              totalBilled = 0.0,
              totalPaid = 0.0,
              balanceDue = 0.0,
            )
          ),
      )

    composeRule.setContent {
      SobjiKhataTheme {
        NewPurchaseScreen(
          uiState = uiState,
          onAddDraftItem = { _, _, _ -> },
          onRemoveDraftItem = {},
          onClearDraft = {},
          onCreateSupplier = { _, _ -> supplier },
          onSavePurchase = { _, _, _, paid, _, _, _ ->
            savedPaid = paid
            savedDue = 300.0 - paid
          },
          onBack = {},
        )
      }
    }
    composeRule.waitForIdle()

    // Clearing paidText must set dueAmount = 300 (showing credit warning) and require supplier
    composeRule.onNodeWithTag("purchase_paid_input").performScrollTo().performTextClearance()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("save_purchase_button").performScrollTo().performClick()
    composeRule.waitForIdle()
    composeRule
      .onNodeWithText("বাকি রাখার জন্য অনুগ্রহ করে পাইকার নির্বাচন করুন।")
      .assertIsDisplayed()

    // Select supplier & save -> savedPaid == 0.0, savedDue == 300.0
    composeRule.onNodeWithText("রশিদ আড়ত").performScrollTo().performClick()
    composeRule.onNodeWithTag("save_purchase_button").performScrollTo().performClick()
    composeRule.waitForIdle()
    assertEquals(0.0, savedPaid, 0.001)
    assertEquals(300.0, savedDue, 0.001)
  }

  @Test
  fun `TC-UI-02 BUG-003 fix checkout rejects negative paid or negative discount inputs cleanly`() {
    val veg =
      VegetableEntity(
        id = 1L,
        name = "পেঁয়াজ",
        category = VegetableCategory.ROOT,
        unitType = UnitType.KG,
        stockUnits = 10_000L,
        avgCostPerUnit = 60.0,
        sellingPricePerUnit = 80.0,
      )
    var submitted = false
    val uiState =
      ShopUiState(
        isLoaded = true,
        preferences = ShopPreferences(isSetupComplete = true, showCreditLedger = true),
        activeVegetables = listOf(veg),
        posCart = listOf(PosCartItem(veg, 1_000L, 80.0, 80.0)),
      )

    composeRule.setContent {
      SobjiKhataTheme {
        QuickPosScreen(
          uiState = uiState,
          onOpenHistory = {},
          onOpenStock = {},
          onAddToCart = { _, _, _ -> },
          onRemoveFromCart = {},
          onClearCart = {},
          onCompleteSale = { _, _, _, _, _, _, _ -> submitted = true },
          onCreateCustomer = { n, p -> PartyEntity(1L, n, p, PartyType.CUSTOMER) },
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("pos_cart_details_button").performClick()
    composeRule.waitForIdle()

    // Enter negative paid amount "-20"
    composeRule.onNodeWithTag("checkout_paid_input").performScrollTo().performTextClearance()
    composeRule.onNodeWithTag("checkout_paid_input").performTextInput("-20")
    composeRule.onNodeWithTag("checkout_complete_button").performScrollTo().performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("সঠিক পরিশোধিত টাকার পরিমাণ লিখুন।").assertIsDisplayed()
    assertTrue(!submitted)

    // Restore valid paid amount & enter negative discount "-5"
    composeRule.onNodeWithTag("checkout_paid_input").performScrollTo().performTextClearance()
    composeRule.onNodeWithTag("checkout_paid_input").performTextInput("80")
    composeRule.onNodeWithTag("checkout_discount_input").performScrollTo().performTextInput("-5")
    composeRule.onNodeWithTag("checkout_complete_button").performScrollTo().performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("সঠিক ছাড়ের পরিমাণ লিখুন।").assertIsDisplayed()
    assertTrue(!submitted)
  }

  @Test
  fun `TC-UI-03 BUG-002 fix decimal precision preserved when editing vegetable prices and expenses`() {
    val veg =
      VegetableEntity(
        id = 5L,
        name = "গাজর",
        category = VegetableCategory.ROOT,
        unitType = UnitType.KG,
        stockUnits = 3_000L,
        avgCostPerUnit = 42.67,
        sellingPricePerUnit = 55.5,
      )
    val state =
      ShopUiState(
        isLoaded = true,
        preferences = ShopPreferences(isSetupComplete = true),
        allVegetables = listOf(veg),
        activeVegetables = listOf(veg),
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
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("stock_menu_5").performClick()
    composeRule.onNodeWithText("তথ্য ও দর বদলান").performClick()
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("veg_cost_price_input").assertTextContains("42.67")
    composeRule.onNodeWithTag("veg_sell_price_input").assertTextContains("55.5")
  }

  @Test
  fun `TC-UI-04 BUG-002 expense edit preserves decimal and BUG-005 settings blank name shows inline error`() {
    var updated = false
    composeRule.setContent {
      SobjiKhataTheme {
        AddEditExpenseSheet(
          existing = ExpenseEntity(id = 2L, category = ExpenseCategory.UTILITY, amount = 150.75),
          onDismiss = {},
          onSave = { _, _, _, _ -> },
        )
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("expense_amount_input").assertTextContains("150.75")
  }

  @Test
  fun `TC-UI-05 settings blank shop name displays error message instead of failing silently`() {
    var updated = false
    composeRule.setContent {
      SobjiKhataTheme {
        SettingsScreen(
          uiState = ShopUiState(preferences = ShopPreferences(shopName = "তাজা বাজার")),
          pendingRestore = null,
          onBack = {},
          onUpdateShopProfile = { _, _ -> updated = true },
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

    composeRule.onNodeWithTag("settings_shop_name_input").performTextClearance()
    composeRule.onNodeWithTag("settings_update_profile_button").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("অনুগ্রহ করে দোকানের নাম লিখুন।").assertIsDisplayed()
    assertTrue(!updated)
  }

  @Test
  @Config(sdk = [36], qualifiers = "w900dp-h1200dp-mdpi")
  fun `TC-UI-06 expanded tablet two-pane POS layout and 130 percent font scale render cleanly without overflow`() {
    val tomato =
      VegetableEntity(
        id = 1L,
        name = "টমেটো",
        category = VegetableCategory.FRUIT_VEG,
        unitType = UnitType.KG,
        stockUnits = 10_000L,
        avgCostPerUnit = 40.0,
        sellingPricePerUnit = 60.0,
      )
    val state =
      ShopUiState(
        isLoaded = true,
        preferences = ShopPreferences(isSetupComplete = true),
        activeVegetables = listOf(tomato),
        posCart = listOf(PosCartItem(tomato, 2_000L, 60.0, 120.0)),
      )

    composeRule.setContent {
      CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) {
        SobjiKhataTheme {
          QuickPosScreen(
            uiState = state,
            onOpenHistory = {},
            onOpenStock = {},
            onAddToCart = { _, _, _ -> },
            onRemoveFromCart = {},
            onClearCart = {},
            onCompleteSale = { _, _, _, _, _, _, _ -> },
            onCreateCustomer = { n, p -> PartyEntity(1L, n, p, PartyType.CUSTOMER) },
          )
        }
      }
    }
    composeRule.waitForIdle()

    // On >= 840dp width, the persistent right-side two-pane cart panel is shown instead of bottom
    // bar
    composeRule.onNodeWithText("ক্রেতার বিল").assertIsDisplayed()
    composeRule.onNodeWithText("চেকআউট ও পেমেন্ট").assertIsDisplayed()
    composeRule.onNodeWithTag("pos_sticky_cart_bar").assertDoesNotExist()
  }
}
