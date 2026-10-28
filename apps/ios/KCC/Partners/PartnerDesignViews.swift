import SwiftUI

struct PartnersTabs: View {
    @Binding var selection: PartnersSection

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: KccSpacing.s2) {
                ForEach(PartnersSection.allCases) { section in
                    Button { selection = section } label: {
                        Text(section.titleKey).font(.body.weight(.semibold)).fixedSize()
                            .padding(KccSpacing.s3).frame(minHeight: 48)
                            .foregroundStyle(selection == section ? KccTheme.onDriver : KccTheme.text)
                            .background(selection == section ? KccTheme.driver : KccTheme.subtle, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("partners.tab.\(section.rawValue)")
                    .accessibilityAddTraits(selection == section ? [.isSelected] : [])
                }
            }.padding(KccSpacing.s3)
        }
        .fixedSize(horizontal: false, vertical: true)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("partners.tabs")
    }
}

struct PartnerCompanySummary: View {
    let company: PartnerCompany
    let offerCount: Int?
    var showsChevron = true

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            HStack(alignment: .top, spacing: KccSpacing.s3) {
                Image(systemName: "building.2.fill").font(.system(size: 20))
                    .foregroundStyle(KccTheme.driver).frame(width: 40, height: 40)
                    .background(KccTheme.subtle, in: RoundedRectangle(cornerRadius: KccRadius.sm))
                    .accessibilityHidden(true)
                Text(verbatim: company.name).font(.title3.bold())
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if showsChevron {
                    Image(systemName: "chevron.right").font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(KccTheme.secondary).accessibilityHidden(true)
                }
            }
            Text(LocalizedStringKey(company.category.localizationKey)).font(.subheadline)
                .foregroundStyle(KccTheme.driver).fixedSize(horizontal: false, vertical: true)
            if let offerCount {
                let key = offerCount == 1 ? "partnerOffers.offerCountOne" : "partnerOffers.offerCountOther"
                Text(verbatim: String.localizedStringWithFormat(NSLocalizedString(key, comment: "Actual active offer count"), Int64(offerCount)))
                    .font(.footnote).foregroundStyle(KccTheme.secondary)
            }
        }
        .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
    }
}

struct PartnerSavedOfferSummary: View {
    let offer: PartnerOffer
    let companyName: String

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            Text(verbatim: offer.title).font(.headline).fixedSize(horizontal: false, vertical: true)
            Text(verbatim: companyName).font(.body).foregroundStyle(KccTheme.secondary)
                .fixedSize(horizontal: false, vertical: true)
            PartnerOfferTypeLabel(type: offer.offerType)
        }.frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
    }
}

struct PartnerOfferTypeLabel: View {
    let type: PartnerOfferType
    var body: some View {
        Text(LocalizedStringKey(type.localizationKey)).font(.footnote.weight(.semibold))
            .foregroundStyle(KccTheme.driver).fixedSize(horizontal: false, vertical: true)
            .padding(KccSpacing.s2)
            .background(KccTheme.subtle, in: RoundedRectangle(cornerRadius: KccRadius.sm))
    }
}

struct PartnerOfferCodeValue: View {
    let code: String
    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            Text("partnerOffers.codeVisible").font(.footnote).foregroundStyle(KccTheme.secondary)
            Text(verbatim: code).font(.title3.monospaced().weight(.semibold))
                .fixedSize(horizontal: false, vertical: true).textSelection(.enabled)
        }
        .padding(KccSpacing.s3).frame(maxWidth: .infinity, alignment: .leading)
        .background(KccTheme.subtle, in: RoundedRectangle(cornerRadius: KccRadius.md))
    }
}

struct PartnerApplicationTextField: View {
    let title: LocalizedStringKey
    @Binding var text: String
    var multiline = false
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s1) {
            // The field keeps this accessible label even after its placeholder disappears.
            Text(title).font(.footnote).foregroundStyle(KccTheme.secondary).accessibilityHidden(true)
            TextField(title, text: $text, axis: multiline ? .vertical : .horizontal)
                .font(.body).frame(minHeight: 48)
                .lineLimit(multiline ? (typeSize.isAccessibilitySize ? 1...2 : 3...6) : 1...1)
        }
    }
}

struct PartnerApplicationSubmitLabel: View {
    let submitting: Bool
    var body: some View {
        HStack(spacing: KccSpacing.s2) {
            if submitting { ProgressView().accessibilityHidden(true) }
            Text(submitting ? "partners.submitting" : "partners.submitButton")
                .font(.body.weight(.semibold)).fixedSize(horizontal: false, vertical: true)
        }.frame(maxWidth: .infinity, minHeight: 48)
    }
}
