/**
 * PartnerApplicationScreen — business partner application form.
 *
 * Allows authenticated users to submit a partner application on behalf of
 * their company. Unauthenticated users are shown a prompt to log in first.
 *
 * Privacy rules:
 *  - Contact details are used only for the application process.
 *  - Privacy notice is shown before submission.
 *  - Contact details are never published.
 *  - Submitted text is plain text — never rendered as HTML.
 *  - Backend rate-limits and blocks duplicate active applications.
 *
 * Accessibility:
 *  - All inputs have accessibilityLabel.
 *  - Form errors use accessibilityLiveRegion.
 */

import { useCallback, useRef, useState } from 'react';
import {
  KeyboardAvoidingView,
  Platform,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';

import type { PartnerCategory, SubmitPartnerApplicationRequest } from '@carcommunity/shared/partners';
import { PARTNER_CATEGORIES } from '@carcommunity/shared/partners';

import { submitPartnerApplication, PartnerApiError } from '../api/partners';
import { useAppTheme } from '../hooks/useAppTheme';
import { useAuth } from '../hooks/useAuth';
import { useI18n } from '../hooks/useI18n';
import { loadSessionToken } from '../storage/tokenStorage';
import type { RootStackParamList } from '../navigation/types';

type Props = NativeStackScreenProps<RootStackParamList, 'PartnerApplication'>;

const CATEGORY_LABELS: Record<PartnerCategory, string> = {
  workshop: 'Verkstad',
  car_care: 'Bilvård',
  parts: 'Reservdelar',
  tires: 'Däck',
  charging: 'Laddning',
  restaurant: 'Restaurang',
  retail: 'Butik',
  other: 'Annat',
};

export const PartnerApplicationScreen = ({ navigation }: Props) => {
  const { theme } = useAppTheme();
  const { t } = useI18n();
  const { isAuthenticated } = useAuth();

  const [companyName, setCompanyName] = useState('');
  const [category, setCategory] = useState<PartnerCategory>('other');
  const [contactName, setContactName] = useState('');
  const [contactEmail, setContactEmail] = useState('');
  const [contactPhone, setContactPhone] = useState('');
  const [website, setWebsite] = useState('');
  const [message, setMessage] = useState('');

  const [isSubmitting, setIsSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [isSuccess, setIsSuccess] = useState(false);

  const isMounted = useRef(true);

  const handleSubmit = useCallback(async () => {
    setSubmitError(null);

    if (!companyName.trim()) {
      setSubmitError(t('partners.fieldRequired'));
      return;
    }
    if (!contactName.trim()) {
      setSubmitError(t('partners.fieldRequired'));
      return;
    }
    if (!contactEmail.trim()) {
      setSubmitError(t('partners.fieldRequired'));
      return;
    }

    setIsSubmitting(true);
    try {
      const token = await loadSessionToken();

      const request: SubmitPartnerApplicationRequest = {
        companyName: companyName.trim(),
        category,
        contactName: contactName.trim(),
        contactEmail: contactEmail.trim(),
        contactPhone: contactPhone.trim() || null,
        websiteUrl: website.trim() || null,
        message: message.trim() || null,
      };

      await submitPartnerApplication(request, token ?? undefined);

      if (isMounted.current) {
        setIsSuccess(true);
      }
    } catch (err) {
      if (isMounted.current) {
        if (err instanceof PartnerApiError && err.statusCode === 409) {
          setSubmitError(t('partners.duplicateApplication'));
        } else {
          setSubmitError(err instanceof PartnerApiError ? err.message : t('partners.error'));
        }
      }
    } finally {
      if (isMounted.current) setIsSubmitting(false);
    }
  }, [companyName, category, contactName, contactEmail, contactPhone, website, message, t]);

  if (!isAuthenticated) {
    return (
      <View style={[styles.container, { backgroundColor: theme.colors.pageBackground }]}>
        <Text style={[styles.infoText, { color: theme.colors.textSecondary }]}>
          Du måste vara inloggad för att skicka in en ansökan.
        </Text>
      </View>
    );
  }

  if (isSuccess) {
    return (
      <View style={[styles.container, { backgroundColor: theme.colors.pageBackground }]}>
        <Text style={[styles.successText, { color: theme.colors.textPrimary }]}>
          {t('partners.submitSuccess')}
        </Text>
        <TouchableOpacity
          style={[styles.button, { backgroundColor: theme.colors.brandPrimary }]}
          onPress={() => navigation.goBack()}
          accessibilityRole="button"
        >
          <Text style={styles.buttonText}>{t('partners.close')}</Text>
        </TouchableOpacity>
      </View>
    );
  }

  return (
    <KeyboardAvoidingView
      style={{ flex: 1 }}
      behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
    >
      <ScrollView
        style={{ backgroundColor: theme.colors.pageBackground }}
        contentContainerStyle={styles.scrollContent}
        keyboardShouldPersistTaps="handled"
      >
        <Text style={[styles.title, { color: theme.colors.textPrimary }]}>
          {t('partners.applicationSubtitle')}
        </Text>

        {/* Privacy notice */}
        <View style={[styles.privacyNotice, { backgroundColor: theme.colors.surfaceAlt }]}>
          <Text style={[styles.privacyText, { color: theme.colors.textSecondary }]}>
            {t('partners.privacyNotice')}
          </Text>
        </View>

        {/* Company name */}
        <Text style={[styles.label, { color: theme.colors.textPrimary }]}>
          {t('partners.companyNameLabel')} *
        </Text>
        <TextInput
          style={[styles.input, { color: theme.colors.textPrimary, borderColor: theme.colors.borderDefault }]}
          value={companyName}
          onChangeText={setCompanyName}
          maxLength={150}
          autoCapitalize="words"
          accessibilityLabel={t('partners.companyNameLabel')}
        />

        {/* Category — simple text field for MVP */}
        <Text style={[styles.label, { color: theme.colors.textPrimary }]}>
          {t('partners.categoryLabel')} *
        </Text>
        <View style={styles.categoryRow}>
          {PARTNER_CATEGORIES.map((cat) => (
            <TouchableOpacity
              key={cat}
              style={[
                styles.categoryChip,
                category === cat
                  ? { backgroundColor: theme.colors.brandPrimary }
                  : { borderColor: theme.colors.borderDefault, borderWidth: 1 },
              ]}
              onPress={() => setCategory(cat)}
              accessibilityRole="radio"
              accessibilityState={{ checked: category === cat }}
              accessibilityLabel={CATEGORY_LABELS[cat]}
            >
              <Text
                style={[
                  styles.categoryChipText,
                  { color: category === cat ? '#FFFFFF' : theme.colors.textPrimary },
                ]}
              >
                {CATEGORY_LABELS[cat]}
              </Text>
            </TouchableOpacity>
          ))}
        </View>

        {/* Contact name */}
        <Text style={[styles.label, { color: theme.colors.textPrimary }]}>
          {t('partners.contactNameLabel')} *
        </Text>
        <TextInput
          style={[styles.input, { color: theme.colors.textPrimary, borderColor: theme.colors.borderDefault }]}
          value={contactName}
          onChangeText={setContactName}
          maxLength={120}
          autoCapitalize="words"
          accessibilityLabel={t('partners.contactNameLabel')}
        />

        {/* Contact email */}
        <Text style={[styles.label, { color: theme.colors.textPrimary }]}>
          {t('partners.contactEmailLabel')} *
        </Text>
        <TextInput
          style={[styles.input, { color: theme.colors.textPrimary, borderColor: theme.colors.borderDefault }]}
          value={contactEmail}
          onChangeText={setContactEmail}
          maxLength={254}
          keyboardType="email-address"
          autoCapitalize="none"
          autoCorrect={false}
          accessibilityLabel={t('partners.contactEmailLabel')}
        />

        {/* Contact phone (optional) */}
        <Text style={[styles.label, { color: theme.colors.textPrimary }]}>
          {t('partners.contactPhoneLabel')}
        </Text>
        <TextInput
          style={[styles.input, { color: theme.colors.textPrimary, borderColor: theme.colors.borderDefault }]}
          value={contactPhone}
          onChangeText={setContactPhone}
          maxLength={30}
          keyboardType="phone-pad"
          accessibilityLabel={t('partners.contactPhoneLabel')}
        />

        {/* Website (optional) */}
        <Text style={[styles.label, { color: theme.colors.textPrimary }]}>
          {t('partners.websiteLabel')}
        </Text>
        <TextInput
          style={[styles.input, { color: theme.colors.textPrimary, borderColor: theme.colors.borderDefault }]}
          value={website}
          onChangeText={setWebsite}
          maxLength={500}
          keyboardType="url"
          autoCapitalize="none"
          autoCorrect={false}
          accessibilityLabel={t('partners.websiteLabel')}
        />

        {/* Message (optional) */}
        <Text style={[styles.label, { color: theme.colors.textPrimary }]}>
          {t('partners.messageLabel')}
        </Text>
        <TextInput
          style={[styles.inputMultiline, { color: theme.colors.textPrimary, borderColor: theme.colors.borderDefault }]}
          value={message}
          onChangeText={setMessage}
          maxLength={2000}
          multiline
          numberOfLines={4}
          textAlignVertical="top"
          accessibilityLabel={t('partners.messageLabel')}
        />

        {submitError !== null && (
          <Text
            style={[styles.errorText, { color: theme.colors.statusError }]}
            accessibilityLiveRegion="polite"
          >
            {submitError}
          </Text>
        )}

        <TouchableOpacity
          style={[styles.button, { backgroundColor: theme.colors.brandPrimary }, isSubmitting && styles.buttonDisabled]}
          onPress={() => void handleSubmit()}
          disabled={isSubmitting}
          accessibilityRole="button"
          accessibilityLabel={t('partners.submitButton')}
        >
          <Text style={styles.buttonText}>
            {isSubmitting ? t('partners.submitting') : t('partners.submitButton')}
          </Text>
        </TouchableOpacity>
      </ScrollView>
    </KeyboardAvoidingView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 24,
  },
  scrollContent: {
    padding: 24,
    paddingBottom: 48,
  },
  title: {
    fontSize: 18,
    fontWeight: '600',
    marginBottom: 16,
  },
  privacyNotice: {
    padding: 12,
    borderRadius: 8,
    marginBottom: 24,
  },
  privacyText: {
    fontSize: 13,
    lineHeight: 19,
  },
  label: {
    fontSize: 14,
    fontWeight: '500',
    marginBottom: 6,
    marginTop: 16,
  },
  input: {
    borderWidth: 1,
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 15,
  },
  inputMultiline: {
    borderWidth: 1,
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 15,
    minHeight: 96,
  },
  categoryRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  categoryChip: {
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 16,
  },
  categoryChipText: {
    fontSize: 13,
    fontWeight: '500',
  },
  button: {
    paddingVertical: 14,
    borderRadius: 8,
    alignItems: 'center',
    marginTop: 24,
  },
  buttonDisabled: {
    opacity: 0.6,
  },
  buttonText: {
    color: '#FFFFFF',
    fontSize: 15,
    fontWeight: '600',
  },
  successText: {
    fontSize: 17,
    fontWeight: '600',
    textAlign: 'center',
    marginBottom: 24,
  },
  infoText: {
    fontSize: 15,
    textAlign: 'center',
    paddingHorizontal: 24,
  },
  errorText: {
    fontSize: 14,
    marginTop: 8,
  },
});
