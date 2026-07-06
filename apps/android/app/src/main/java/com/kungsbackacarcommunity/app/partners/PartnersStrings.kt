package com.kungsbackacarcommunity.app.partners

import androidx.annotation.StringRes
import com.kungsbackacarcommunity.app.R

/** Localized-label lookups for the partner enums (Phase 12 slice 17). */

@StringRes
fun PartnerCategory.labelRes(): Int =
    when (this) {
        PartnerCategory.WORKSHOP -> R.string.partners_categoryWorkshop
        PartnerCategory.CAR_CARE -> R.string.partners_categoryCarCare
        PartnerCategory.PARTS -> R.string.partners_categoryParts
        PartnerCategory.TIRES -> R.string.partners_categoryTires
        PartnerCategory.CHARGING -> R.string.partners_categoryCharging
        PartnerCategory.RESTAURANT -> R.string.partners_categoryRestaurant
        PartnerCategory.RETAIL -> R.string.partners_categoryRetail
        PartnerCategory.OTHER -> R.string.partners_categoryOther
    }

@StringRes
fun PartnerOfferType.labelRes(): Int =
    when (this) {
        PartnerOfferType.DISCOUNT_CODE -> R.string.partnerOffers_offerTypeDiscountCode
        PartnerOfferType.PERCENTAGE_DISCOUNT -> R.string.partnerOffers_offerTypePercentageDiscount
        PartnerOfferType.FIXED_DISCOUNT -> R.string.partnerOffers_offerTypeFixedDiscount
        PartnerOfferType.MEMBER_BENEFIT -> R.string.partnerOffers_offerTypeMemberBenefit
        PartnerOfferType.SPECIAL_OFFER -> R.string.partnerOffers_offerTypeSpecialOffer
        PartnerOfferType.OTHER -> R.string.partnerOffers_offerTypeOther
    }
