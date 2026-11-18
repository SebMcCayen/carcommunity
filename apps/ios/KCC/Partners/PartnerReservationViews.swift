import SwiftUI
import CoreImage.CIFilterBuiltins
import UIKit

struct PartnerReservationSheet: View {
    let offerId: String
    @State private var response: PortalOfferResponse?
    @State private var reservation: PortalReservation?
    @State private var busy = false
    @State private var errorKey: String?
    @State private var requestId = UUID().uuidString
    @State private var termsAccepted = false
    @Environment(\.dismiss) private var dismiss
    private let client = PartnerPortalClient.createIfAvailable()

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: KccSpacing.s4) {
                    if busy { ProgressView() }
                    PortalError(key: errorKey)
                    if errorKey != nil && response != nil && reservation == nil {
                        Button { Task { await load() } } label: { PortalText("portal.retry") }.frame(minHeight: 48)
                    }
                    if let reservation {
                        PartnerReservationContent(reservation: reservation)
                    } else if let response {
                        Text(response.offer.title).font(.title2.bold())
                        Text(response.offer.description)
                        PortalText("portal.terms").font(.headline)
                        Text(response.offer.terms)
                        if let remaining = response.offer.remainingCount {
                            Text("\(portalString("portal.remaining")): \(remaining)")
                        }
                        Text("\(portalString("portal.reservationDays")): \(response.offer.reservationDays)")
                        PortalText("portal.noAppointments").font(.footnote).foregroundStyle(.secondary)
                        PortalText("portal.redemptionStatisticsNotice").font(.footnote).foregroundStyle(.secondary)
                        Toggle(portalString("portal.acceptTerms"), isOn: $termsAccepted)
                        Button { Task { await reserve(response.offer) } } label: { PortalText("portal.reserve") }
                            .kccPrimaryAction().disabled(busy || !termsAccepted || response.offer.remainingCount == 0)
                    } else if !busy {
                        Button { Task { await load() } } label: { PortalText("portal.retry") }.frame(minHeight: 48)
                    }
                }.padding(KccSpacing.s4)
            }
            .navigationTitle(portalString("portal.reserve"))
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("partners.close") { dismiss() } } }
            .task { await load() }
        }.kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
    }
    private func load() async {
        guard let client, !busy else { errorKey = "portal.unavailable"; return }
        busy = true; errorKey = nil; termsAccepted = false
        defer { busy = false }
        do {
            let value = try await client.call("offer", ["offerId": offerId], as: PortalOfferResponse.self)
            guard !Task.isCancelled else { return }
            response = value
            if let existing = value.reservation, !["cancelled", "expired"].contains(existing.status) {
                reservation = existing
            } else { reservation = nil }
        } catch { errorKey = PartnerPortalCoordinator.message(error) }
    }
    private func reserve(_ offer: PortalOffer) async {
        guard let client, !busy else { return }
        busy = true; errorKey = nil
        defer { busy = false }
        do {
            let value = try await client.call("reserve", ["offerId": offerId, "requestId": requestId,
                "expectedVersion": offer.version], as: PortalReservation.self)
            guard !Task.isCancelled else { return }
            reservation = value
        } catch { errorKey = PartnerPortalCoordinator.message(error) }
    }
}

struct PartnerMyReservationsScreen: View {
    @State private var reservations: [PortalReservation] = []
    @State private var busy = false
    @State private var errorKey: String?
    private let client = PartnerPortalClient.createIfAvailable()
    let onBack: () -> Void
    var body: some View {
        NavigationStack {
            List {
                if busy { ProgressView() }
                PortalError(key: errorKey)
                if reservations.isEmpty && !busy { PortalText("portal.noReservations") }
                ForEach(reservations) { reservation in
                    NavigationLink {
                        ScrollView { PartnerReservationContent(reservation: reservation).padding(KccSpacing.s4) }
                            .navigationTitle(portalString("portal.reservation"))
                    } label: {
                        VStack(alignment: .leading, spacing: KccSpacing.s1) {
                            Text(reservation.title).font(.headline)
                            Text(reservation.companyName)
                            PortalText("portal.reservationStatus.\(reservation.status)").font(.footnote).foregroundStyle(.secondary)
                        }.frame(minHeight: 64)
                    }
                }
            }
            .task { await load() }
            .refreshable { await load() }
            .navigationTitle(portalString("portal.myReservations"))
            .toolbar { ToolbarItem(placement: .topBarLeading) { Button(action: onBack) { Label("shell.back", systemImage: "chevron.backward") } } }
        }.kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
    }
    private func load() async {
        guard let client, !busy else { return }
        busy = true; errorKey = nil
        defer { busy = false }
        do {
            let response = try await client.call("reservations", as: PortalReservationsResponse.self)
            guard !Task.isCancelled else { return }
            reservations = response.reservations
        } catch { reservations = []; errorKey = PartnerPortalCoordinator.message(error) }
    }
}

struct PartnerReservationContent: View {
    @State var reservation: PortalReservation
    @State private var token: PortalToken?
    @State private var busy = false
    @State private var errorKey: String?
    @State private var confirmCancellation = false
    @Environment(\.scenePhase) private var scenePhase
    private let client = PartnerPortalClient.createIfAvailable()
    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s4) {
            Text(reservation.title).font(.title2.bold())
            Text(reservation.companyName).font(.headline)
            PortalText("portal.reservationStatus.\(reservation.status)")
            Text(reservation.terms)
            if let expiry = reservation.expiry {
                Text("\(portalString("portal.expires")): \(expiry.formatted(date: .abbreviated, time: .shortened))")
            }
            PortalText("portal.noAppointments").font(.footnote).foregroundStyle(.secondary)
            PortalError(key: errorKey)
            if busy { ProgressView() }
            if reservation.canPresent {
                if let token {
                    TimelineView(.periodic(from: .now, by: 1)) { context in
                        if let expiry = token.expiry, expiry > context.date, scenePhase == .active,
                           let image = PortalQR.image(token.token) {
                            VStack(spacing: KccSpacing.s2) {
                                Image(uiImage: image).interpolation(.none).resizable().scaledToFit()
                                    .frame(maxWidth: 280, maxHeight: 280).padding(20).background(.white)
                                    .accessibilityLabel(portalString("portal.qrDescription"))
                                PortalText("portal.showAtArrival").font(.footnote)
                                Text("\(portalString("portal.qrSeconds")): \(max(0, Int(expiry.timeIntervalSince(context.date))))")
                                    .font(.caption).monospacedDigit().accessibilityHidden(true)
                            }.accessibilityElement(children: .combine)
                        } else { PortalText("portal.tokenExpired") }
                    }
                }
                Button { Task { await issueToken() } } label: { PortalText(token == nil ? "portal.showQR" : "portal.refreshQR") }
                    .kccPrimaryAction().disabled(busy || scenePhase != .active)
                Button(role: .destructive) { confirmCancellation = true } label: { PortalText("portal.cancelReservation") }
                    .frame(minHeight: 48).disabled(busy)
            }
        }
        .confirmationDialog(portalString("portal.cancelConfirm"), isPresented: $confirmCancellation, titleVisibility: .visible) {
            Button(portalString("portal.cancelReservation"), role: .destructive) { Task { await cancel() } }
            Button("partners.close", role: .cancel) {}
        }
        .onChange(of: scenePhase) { _, phase in if phase != .active { token = nil } }
        .onDisappear { token = nil }
        .accessibilityIdentifier("partnerPortal.reservation")
    }
    private func issueToken() async {
        guard let client, !busy else { return }
        token = nil; busy = true; errorKey = nil
        defer { busy = false }
        do {
            let value = try await client.call("token", ["reservationId": reservation.reservationId], as: PortalToken.self)
            guard !Task.isCancelled, scenePhase == .active else { return }
            token = value
        } catch { errorKey = PartnerPortalCoordinator.message(error) }
    }
    private func cancel() async {
        guard let client, !busy else { return }
        token = nil; busy = true; errorKey = nil
        defer { busy = false }
        do {
            let value = try await client.call("cancel", ["reservationId": reservation.reservationId], as: PortalReservation.self)
            guard !Task.isCancelled else { return }
            reservation = value
        } catch { errorKey = PartnerPortalCoordinator.message(error) }
    }
}

enum PortalQR {
    static func image(_ token: String) -> UIImage? {
        guard !token.isEmpty, token.utf8.count <= 2_048 else { return nil }
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(token.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage,
              let image = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: image)
    }
}
