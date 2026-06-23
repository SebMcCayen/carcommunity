/**
 * VehicleDetailScreen — private vehicle detail view.
 *
 * Shows the full detail of a single vehicle profile owned by the current user.
 * Provides navigation to the edit form and delete confirmation.
 *
 * Privacy:
 *  - Displays only the owning user's vehicle.
 *  - No registration numbers, VIN, insurance data, or location.
 */

import {
  ActivityIndicator,
  Alert,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';

import type { NativeStackScreenProps } from '@react-navigation/native-stack';

import { useAppTheme } from '../hooks/useAppTheme';
import { useI18n } from '../hooks/useI18n';
import { useGarageDetail } from '../hooks/useGarage';
import { KccButton } from '../components/KccButton';
import { ScreenContainer } from '../components/ScreenContainer';
import type { RootStackParamList } from '../navigation/types';

type Props = NativeStackScreenProps<RootStackParamList, 'VehicleDetail'>;

export const VehicleDetailScreen = ({ route, navigation }: Props) => {
  const { vehicleId } = route.params;
  const { theme } = useAppTheme();
  const { t } = useI18n();
  const { vehicle, isLoading, error, refresh, deleteVehicle } = useGarageDetail(vehicleId);

  const handleDelete = () => {
    Alert.alert(
      t('garage.deleteVehicle'),
      t('garage.deleteConfirm'),
      [
        { text: t('garage.cancelButton'), style: 'cancel' },
        {
          text: t('garage.deleteConfirmButton'),
          style: 'destructive',
          onPress: () => {
            void (async () => {
              const wasDeleted = await deleteVehicle();
              if (wasDeleted) {
                navigation.goBack();
              } else {
                Alert.alert(t('garage.deleteVehicle'), t('garage.deleteError'));
              }
            })();
          },
        },
      ],
    );
  };

  if (isLoading) {
    return (
      <ScreenContainer>
        <ActivityIndicator color={theme.colors.brandPrimary} style={styles.loader} />
      </ScreenContainer>
    );
  }

  if (error || !vehicle) {
    return (
      <ScreenContainer>
        <Text style={[styles.errorText, { color: theme.colors.statusError }]}>
          {t('garage.errorDetail')}
        </Text>
        <KccButton label={t('garage.retryButton')} onPress={refresh} variant="secondary" />
      </ScreenContainer>
    );
  }

  const powertrainLabel = t(`garage.powertrain_${vehicle.powertrain}` as Parameters<typeof t>[0]);

  return (
    <ScreenContainer>
      <ScrollView contentContainerStyle={{ padding: theme.spacing[4] }}>
        <View
          style={[
            styles.card,
            {
              backgroundColor: theme.colors.surfaceBackground,
              borderColor: theme.colors.borderDefault,
              borderRadius: theme.radius.lg,
              padding: theme.spacing[4],
              marginBottom: theme.spacing[4],
            },
          ]}
        >
          <Text style={[styles.title, { color: theme.colors.textPrimary }]}>
            {vehicle.make} {vehicle.model}
          </Text>
          <View style={styles.row}>
            <Text style={[styles.label, { color: theme.colors.textSecondary }]}>
              {t('garage.modelYear')}
            </Text>
            <Text style={[styles.value, { color: theme.colors.textPrimary }]}>
              {vehicle.modelYear}
            </Text>
          </View>
          <View style={styles.row}>
            <Text style={[styles.label, { color: theme.colors.textSecondary }]}>
              {t('garage.powertrain')}
            </Text>
            <Text style={[styles.value, { color: theme.colors.textPrimary }]}>
              {powertrainLabel}
            </Text>
          </View>
          {vehicle.engineDescription ? (
            <View style={styles.row}>
              <Text style={[styles.label, { color: theme.colors.textSecondary }]}>
                {t('garage.engineDescription')}
              </Text>
              <Text style={[styles.value, { color: theme.colors.textPrimary }]}>
                {vehicle.engineDescription}
              </Text>
            </View>
          ) : null}
          {vehicle.description ? (
            <View style={[styles.row, styles.descriptionRow]}>
              <Text style={[styles.label, { color: theme.colors.textSecondary }]}>
                {t('garage.description')}
              </Text>
              <Text style={[styles.descriptionValue, { color: theme.colors.textPrimary }]}>
                {vehicle.description}
              </Text>
            </View>
          ) : null}
        </View>

        <KccButton
          label={t('garage.editVehicle')}
          onPress={() => navigation.navigate('VehicleForm', { vehicleId: vehicle.id })}
          testID="edit-vehicle-button"
        />
        <View style={{ height: theme.spacing[3] }} />
        <KccButton
          label={t('garage.deleteVehicle')}
          onPress={handleDelete}
          variant="destructive"
          testID="delete-vehicle-button"
        />
      </ScrollView>
    </ScreenContainer>
  );
};

const styles = StyleSheet.create({
  loader: {
    marginTop: 40,
  },
  errorText: {
    textAlign: 'center',
    fontSize: 15,
    margin: 24,
  },
  card: {
    borderWidth: 1,
  },
  title: {
    fontSize: 20,
    fontWeight: '700',
    marginBottom: 12,
  },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    marginBottom: 8,
  },
  descriptionRow: {
    flexDirection: 'column',
  },
  label: {
    fontSize: 14,
    fontWeight: '500',
    flex: 1,
  },
  value: {
    fontSize: 14,
    flex: 1,
    textAlign: 'right',
  },
  descriptionValue: {
    fontSize: 14,
    marginTop: 4,
  },
});
