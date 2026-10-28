import SwiftUI

struct CatalogueOptionLabel: View {
    let option: CatalogueOption

    var body: some View {
        HStack(alignment: .top, spacing: KccSpacing.s3) {
            VStack(alignment: .leading, spacing: KccSpacing.s2) {
                if option.isOther {
                    Text("garage.catalogueOther").font(.body)
                    Text("garage.catalogueOtherHint").font(.footnote).foregroundStyle(KccTheme.secondary)
                } else { Text(verbatim: option.name).font(.body) }
            }
            .fixedSize(horizontal: false, vertical: true).frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: "chevron.right").font(.system(size: 14, weight: .semibold))
                .foregroundStyle(KccTheme.secondary).accessibilityHidden(true)
        }.frame(maxWidth: .infinity, minHeight: 48, alignment: .leading).contentShape(Rectangle())
    }
}

struct VehiclePowertrainPicker: View {
    @Binding var selection: VehiclePowertrain?

    var body: some View {
        Menu {
            Picker("garage.powertrain", selection: $selection) {
                Text("garage.validationPowertrainRequired").tag(VehiclePowertrain?.none)
                // Retired vocabulary remains readable elsewhere; new saves
                // still require one of the existing selectable powertrains.
                ForEach(VehiclePowertrain.selectable, id: \.self) { value in
                    Text(LocalizedStringKey(value.localizationKey)).tag(VehiclePowertrain?.some(value))
                }
            }
        } label: {
            EditorSelectionLabel(title: "garage.powertrain",
                value: selection.map { Text(LocalizedStringKey($0.localizationKey)) }, prompt: "garage.validationPowertrainRequired")
        }.buttonStyle(.plain)
    }
}
