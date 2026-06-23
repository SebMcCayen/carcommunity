/**
 * VehicleFormScreen — create or edit a vehicle profile.
 *
 * When vehicleId is provided in route params, the form loads the existing
 * vehicle and submits an update. Otherwise, it creates a new vehicle.
 *
 * Privacy:
 *  - Does not request registration number, VIN, insurance, or location.
 *  - userId is never sent in the request body — ownership is backend-enforced.
 *  - Backend responses are authoritative.
 *
 * UX:
 *  - Prevents duplicate submissions with isSubmitting guard.
 *  - Validates required fields client-side for responsiveness (backend is source of truth).
 *  - Supports loading, empty, error, form-validation, and saving states.
 */

import { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';

import { VEHICLE_POWERTRAINS, type VehiclePowertrain } from '@carcommunity/shared/garage';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';

import { useAppTheme } from '../hooks/useAppTheme';
import { useI18n } from '../hooks/useI18n';
import { useGarageDetail, useGarageForm } from '../hooks/useGarage';
import { KccButton } from '../components/KccButton';
import { ScreenContainer } from '../components/ScreenContainer';
import type { RootStackParamList } from '../navigation/types';

type Props = NativeStackScreenProps<RootStackParamList, 'VehicleForm'>;

const CURRENT_YEAR = new Date().getFullYear();
const MIN_YEAR = 1886;
// MAX_YEAR allows a small future margin. Calculated at module load time;
// if the app stays open across a year boundary the cap may be one year behind
// until the module is reloaded. This is an accepted minor limitation for MVP.
const MAX_YEAR = CURRENT_YEAR + 2;

interface FieldLabelProps {
  label: string;
  required?: boolean;
}

const FieldLabel = ({ label, required }: FieldLabelProps) => {
  const { theme } = useAppTheme();
  return (
    <Text style={[styles.fieldLabel, { color: theme.colors.textSecondary }]}>
      {label}{required ? ' *' : ''}
    </Text>
  );
};

export const VehicleFormScreen = ({ route, navigation }: Props) => {
  const { vehicleId } = route.params ?? {};
  const isEditing = Boolean(vehicleId);

  const { theme } = useAppTheme();
  const { t } = useI18n();

  const { vehicle, isLoading: isLoadingVehicle } = useGarageDetail(vehicleId ?? '');
  const { isSubmitting, error: submitError, createVehicle, updateVehicle } = useGarageForm();

  const [make, setMake] = useState('');
  const [model, setModel] = useState('');
  const [modelYearStr, setModelYearStr] = useState('');
  const [powertrain, setPowertrain] = useState<VehiclePowertrain | ''>('');
  const [engineDescription, setEngineDescription] = useState('');
  const [description, setDescription] = useState('');
  const [validationError, setValidationError] = useState<string | null>(null);

  // Populate form when editing an existing vehicle
  useEffect(() => {
    if (vehicle && isEditing) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- initializes form fields from vehicle prop when entering edit mode
      setMake(vehicle.make);
      setModel(vehicle.model);
      setModelYearStr(String(vehicle.modelYear));
      setPowertrain(vehicle.powertrain);
      setEngineDescription(vehicle.engineDescription ?? '');
      setDescription(vehicle.description ?? '');
    }
  }, [vehicle, isEditing]);

  const validate = (): boolean => {
    if (!make.trim()) {
      setValidationError(t('garage.validationMakeRequired'));
      return false;
    }
    if (!model.trim()) {
      setValidationError(t('garage.validationModelRequired'));
      return false;
    }
    const year = parseInt(modelYearStr, 10);
    if (!modelYearStr.trim()) {
      setValidationError(t('garage.validationModelYearRequired'));
      return false;
    }
    if (isNaN(year) || year < MIN_YEAR || year > MAX_YEAR) {
      setValidationError(t('garage.validationModelYearInvalid'));
      return false;
    }
    if (!powertrain) {
      setValidationError(t('garage.validationPowertrainRequired'));
      return false;
    }
    setValidationError(null);
    return true;
  };

  const handleSubmit = async () => {
    if (!validate()) return;

    const year = parseInt(modelYearStr, 10);
    const body = {
      make: make.trim(),
      model: model.trim(),
      modelYear: year,
      powertrain: powertrain as VehiclePowertrain,
      engineDescription: engineDescription.trim() || undefined,
      description: description.trim() || undefined,
    };

    let result = null;
    if (isEditing && vehicleId) {
      result = await updateVehicle(vehicleId, body);
    } else {
      result = await createVehicle(body);
    }

    if (result) {
      navigation.goBack();
    }
  };

  if (isEditing && isLoadingVehicle) {
    return (
      <ScreenContainer>
        <ActivityIndicator color={theme.colors.brandPrimary} style={styles.loader} />
      </ScreenContainer>
    );
  }

  const powertrainOptions = VEHICLE_POWERTRAINS.map((pt) => ({
    value: pt,
    label: t(`garage.powertrain_${pt}` as Parameters<typeof t>[0]),
  }));

  return (
    <ScreenContainer>
      <ScrollView
        contentContainerStyle={{ padding: theme.spacing[4] }}
        keyboardShouldPersistTaps="handled"
      >
        {(validationError ?? submitError) ? (
          <Text
            style={[styles.errorBanner, { color: theme.colors.statusError }]}
            accessibilityRole="alert"
          >
            {validationError ?? t('garage.saveError')}
          </Text>
        ) : null}

        <FieldLabel label={t('garage.make')} required />
        <TextInput
          value={make}
          onChangeText={setMake}
          maxLength={80}
          accessibilityLabel={t('garage.make')}
          style={[
            styles.input,
            {
              backgroundColor: theme.colors.surfaceBackground,
              borderColor: theme.colors.borderDefault,
              borderRadius: theme.radius.sm,
              color: theme.colors.textPrimary,
              padding: theme.spacing[3],
            },
          ]}
          testID="input-make"
        />

        <FieldLabel label={t('garage.model')} required />
        <TextInput
          value={model}
          onChangeText={setModel}
          maxLength={80}
          accessibilityLabel={t('garage.model')}
          style={[
            styles.input,
            {
              backgroundColor: theme.colors.surfaceBackground,
              borderColor: theme.colors.borderDefault,
              borderRadius: theme.radius.sm,
              color: theme.colors.textPrimary,
              padding: theme.spacing[3],
            },
          ]}
          testID="input-model"
        />

        <FieldLabel label={t('garage.modelYear')} required />
        <TextInput
          value={modelYearStr}
          onChangeText={setModelYearStr}
          keyboardType="number-pad"
          maxLength={4}
          accessibilityLabel={t('garage.modelYear')}
          style={[
            styles.input,
            {
              backgroundColor: theme.colors.surfaceBackground,
              borderColor: theme.colors.borderDefault,
              borderRadius: theme.radius.sm,
              color: theme.colors.textPrimary,
              padding: theme.spacing[3],
            },
          ]}
          testID="input-model-year"
        />

        <FieldLabel label={t('garage.powertrain')} required />
        <View style={styles.powertrainRow}>
          {powertrainOptions.map((opt) => (
            <KccButton
              key={opt.value}
              label={opt.label}
              variant={powertrain === opt.value ? 'primary' : 'secondary'}
              onPress={() => setPowertrain(opt.value)}
              testID={`powertrain-${opt.value}`}
            />
          ))}
        </View>

        <FieldLabel label={t('garage.engineDescription')} />
        <TextInput
          value={engineDescription}
          onChangeText={setEngineDescription}
          maxLength={120}
          accessibilityLabel={t('garage.engineDescription')}
          style={[
            styles.input,
            {
              backgroundColor: theme.colors.surfaceBackground,
              borderColor: theme.colors.borderDefault,
              borderRadius: theme.radius.sm,
              color: theme.colors.textPrimary,
              padding: theme.spacing[3],
            },
          ]}
          testID="input-engine-description"
        />

        <FieldLabel label={t('garage.description')} />
        <TextInput
          value={description}
          onChangeText={setDescription}
          maxLength={500}
          multiline
          numberOfLines={4}
          accessibilityLabel={t('garage.description')}
          style={[
            styles.input,
            styles.textarea,
            {
              backgroundColor: theme.colors.surfaceBackground,
              borderColor: theme.colors.borderDefault,
              borderRadius: theme.radius.sm,
              color: theme.colors.textPrimary,
              padding: theme.spacing[3],
            },
          ]}
          testID="input-description"
        />

        <View style={{ height: theme.spacing[4] }} />
        <KccButton
          label={isEditing ? t('garage.saveVehicle') : t('garage.addVehicle')}
          onPress={() => void handleSubmit()}
          disabled={isSubmitting}
          testID="submit-vehicle-button"
        />
      </ScrollView>
    </ScreenContainer>
  );
};

const styles = StyleSheet.create({
  loader: {
    marginTop: 40,
  },
  errorBanner: {
    fontSize: 14,
    marginBottom: 12,
  },
  fieldLabel: {
    fontSize: 13,
    fontWeight: '500',
    marginTop: 12,
    marginBottom: 4,
  },
  input: {
    borderWidth: 1,
    fontSize: 15,
    minHeight: 44,
  },
  textarea: {
    minHeight: 100,
    textAlignVertical: 'top',
  },
  powertrainRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    marginBottom: 4,
  },
});
