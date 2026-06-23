/**
 * GarageScreen — "Mitt garage" vehicle list.
 *
 * Shows the authenticated user's private vehicle profiles.
 * Free users see a membership notice instead of the garage.
 *
 * Privacy:
 *  - Only shows the current user's vehicles.
 *  - No registration numbers, VIN, or insurance data.
 *  - Vehicle profiles are private.
 *
 * TODO: Add garage-created badge trigger once the badge system is implemented.
 */

import { useEffect } from 'react';
import {
  ActivityIndicator,
  Alert,
  FlatList,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';

import type { VehicleSummary } from '@carcommunity/shared/garage';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useNavigation } from '@react-navigation/native';
import { useAuth } from '../hooks/useAuth';
import { useAppTheme } from '../hooks/useAppTheme';
import { useI18n } from '../hooks/useI18n';
import { useGarageList } from '../hooks/useGarage';
import { KccButton } from '../components/KccButton';
import { LockedFeatureNotice } from '../components/LockedFeatureNotice';
import { ScreenContainer } from '../components/ScreenContainer';
import type { RootStackParamList } from '../navigation/types';
import { canAccessGarage } from '@carcommunity/shared/users';

type GarageNavProp = NativeStackNavigationProp<RootStackParamList>;

interface VehicleCardProps {
  vehicle: VehicleSummary;
  powertrainLabel: string;
  onPress: () => void;
  onDelete: () => void;
  deleteLabel: string;
}

const VehicleCard = ({ vehicle, powertrainLabel, onPress, onDelete, deleteLabel }: VehicleCardProps) => {
  const { theme } = useAppTheme();

  return (
    <Pressable
      onPress={onPress}
      accessibilityRole="button"
      style={[
        styles.card,
        {
          backgroundColor: theme.colors.surfaceBackground,
          borderColor: theme.colors.borderDefault,
          borderRadius: theme.radius.lg,
          padding: theme.spacing[4],
          marginBottom: theme.spacing[3],
        },
      ]}
    >
      <Text style={[styles.cardTitle, { color: theme.colors.textPrimary }]}>
        {vehicle.make} {vehicle.model}
      </Text>
      <Text style={[styles.cardSub, { color: theme.colors.textSecondary }]}>
        {vehicle.modelYear} · {powertrainLabel}
      </Text>
      {vehicle.engineDescription ? (
        <Text style={[styles.cardSub, { color: theme.colors.textSecondary }]}>
          {vehicle.engineDescription}
        </Text>
      ) : null}
      <View style={styles.cardActions}>
        <Pressable
          onPress={(event) => {
            event.stopPropagation();
            onDelete();
          }}
          accessibilityRole="button"
          hitSlop={8}
          style={{ padding: theme.spacing[1] }}
        >
          <Text style={[styles.deleteLink, { color: theme.colors.statusError }]}>{deleteLabel}</Text>
        </Pressable>
      </View>
    </Pressable>
  );
};

export const GarageScreen = () => {
  const navigation = useNavigation<GarageNavProp>();
  const { theme } = useAppTheme();
  const { t } = useI18n();
  const { currentUser } = useAuth();

  const isMember = currentUser
    ? canAccessGarage({
        role: currentUser.roles[0] ?? 'user',
        status: currentUser.status,
        subscriptionEntitlement: currentUser.subscriptionEntitlement,
      })
    : false;

  const { vehicles, isLoading, error, hasNext, loadMore, refresh, deleteVehicle } = useGarageList();

  useEffect(() => {
    const unsubscribe = navigation.addListener('focus', () => {
      void refresh();
    });
    return unsubscribe;
  }, [navigation, refresh]);

  const handleDelete = (vehicleId: string, label: string) => {
    Alert.alert(
      t('garage.deleteVehicle'),
      t('garage.deleteConfirm') + '\n\n' + label,
      [
        { text: t('garage.cancelButton'), style: 'cancel' },
        {
          text: t('garage.deleteConfirmButton'),
          style: 'destructive',
          onPress: () => void deleteVehicle(vehicleId),
        },
      ],
    );
  };

  const powertrainLabel = (pt: VehicleSummary['powertrain']) =>
    t(`garage.powertrain_${pt}` as Parameters<typeof t>[0]);

  if (!isMember) {
    return (
      <ScreenContainer>
        <LockedFeatureNotice
          message={t('garage.memberRequired') + ' ' + t('garage.memberRequiredBody')}
          testID="garage-locked-notice"
        />
      </ScreenContainer>
    );
  }

  return (
    <ScreenContainer>
      <FlatList
        data={vehicles}
        keyExtractor={(v) => v.id}
        contentContainerStyle={styles.listContent}
        onRefresh={refresh}
        refreshing={isLoading && vehicles.length === 0}
        onEndReached={hasNext ? loadMore : undefined}
        onEndReachedThreshold={0.3}
        ListEmptyComponent={
          isLoading ? (
            <ActivityIndicator color={theme.colors.brandPrimary} style={styles.loader} />
          ) : error ? (
            <Text style={[styles.emptyText, { color: theme.colors.statusError }]}>
              {t('garage.error')}
            </Text>
          ) : (
            <Text style={[styles.emptyText, { color: theme.colors.textSecondary }]}>
              {t('garage.empty')}
            </Text>
          )
        }
        renderItem={({ item }) => (
          <VehicleCard
            vehicle={item}
            powertrainLabel={powertrainLabel(item.powertrain)}
            onPress={() =>
              navigation.navigate('VehicleDetail', { vehicleId: item.id })
            }
            onDelete={() => handleDelete(item.id, `${item.make} ${item.model} ${item.modelYear}`)}
            deleteLabel={t('garage.deleteVehicle')}
          />
        )}
      />
      <View style={{ padding: theme.spacing[4], paddingTop: 0 }}>
        <KccButton
          label={t('garage.addVehicle')}
          onPress={() => navigation.navigate('VehicleForm', {})}
          testID="add-vehicle-button"
        />
      </View>
    </ScreenContainer>
  );
};

const styles = StyleSheet.create({
  listContent: {
    padding: 16,
    flexGrow: 1,
  },
  card: {
    borderWidth: 1,
  },
  cardTitle: {
    fontSize: 16,
    fontWeight: '600',
    marginBottom: 4,
  },
  cardSub: {
    fontSize: 14,
    marginBottom: 2,
  },
  cardActions: {
    marginTop: 8,
    flexDirection: 'row',
    justifyContent: 'flex-end',
  },
  deleteLink: {
    fontSize: 13,
    fontWeight: '500',
  },
  emptyText: {
    textAlign: 'center',
    fontSize: 15,
    marginTop: 40,
  },
  loader: {
    marginTop: 40,
  },
});
