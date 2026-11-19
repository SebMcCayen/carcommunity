import { useState } from 'react';
import { PARTNER_CATEGORIES, type PartnerCategory } from '@/features/partners';
import { MapLocationPicker } from '@/components/map/MapLocationPicker';
import { translate } from '@/i18n';
import { p } from './portal-components';
import styles from '../kronjakt/page.module.css';
const t = (key: string) => translate('sv', key);
export interface CompanyFormState {
  companyName: string;
  category: PartnerCategory;
  publicDescription: string;
  address: string;
  latitude: string;
  longitude: string;
  publicPhone: string;
  publicWebsiteUrl: string;
}

export const EMPTY_COMPANY_FORM: CompanyFormState = {
  companyName: '',
  category: 'other',
  publicDescription: '',
  address: '',
  latitude: '',
  longitude: '',
  publicPhone: '',
  publicWebsiteUrl: '',
};

interface CompanyFormProps {
  initial: CompanyFormState;
  onSave: (form: CompanyFormState) => Promise<void>;
  isSaving: boolean;
  saveError: string | null;
}

export const CompanyForm = ({ initial, onSave, isSaving, saveError }: CompanyFormProps) => {
  const [form, setForm] = useState<CompanyFormState>(initial);
  const set = (field: keyof CompanyFormState, value: string) =>
    setForm((prev) => ({ ...prev, [field]: value }));

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        void onSave(form);
      }}
      className={styles.form}
    >
      <div className={styles.formRow}>
        <label className={styles.label}>
          {t('partners.formCompanyNameLabel')} *
          <input
            className={styles.input}
            value={form.companyName}
            onChange={(e) => set('companyName', e.target.value)}
            maxLength={150}
            required
          />
        </label>
      </div>

      <div className={styles.formRow}>
        <label className={styles.label}>
          {t('partners.formCategoryLabel')} *
          <select
            className={styles.input}
            value={form.category}
            onChange={(e) => set('category', e.target.value as PartnerCategory)}
            required
          >
            {PARTNER_CATEGORIES.map((cat) => (
              <option key={cat} value={cat}>
                {p(`category_${cat}`)}
              </option>
            ))}
          </select>
        </label>
      </div>

      <div className={styles.formRow}>
        <label className={styles.label}>
          {t('partners.formDescriptionLabel')} *
          <textarea
            className={styles.input}
            value={form.publicDescription}
            onChange={(e) => set('publicDescription', e.target.value)}
            maxLength={1000}
            rows={3}
            required
          />
        </label>
      </div>

      <div className={styles.formRow}>
        <label className={styles.label}>
          {t('partners.formAddressLabel')} *
          <input
            className={styles.input}
            value={form.address}
            onChange={(e) => set('address', e.target.value)}
            maxLength={300}
            required
          />
        </label>
      </div>

      <MapLocationPicker
        latitude={form.latitude}
        longitude={form.longitude}
        onChange={(latitude, longitude) => setForm((prev) => ({ ...prev, latitude, longitude }))}
        labelLat={t('partners.formLatitudeLabel')}
        labelLng={t('partners.formLongitudeLabel')}
        helpText={t('map.dragHint')}
        unavailableText={t('map.unavailable')}
        loadErrorText={t('map.loadError')}
        required
        labelClassName={styles.label}
        inputClassName={styles.input}
      />

      <div className={styles.formRow}>
        <label className={styles.label}>
          {t('partners.formPhoneLabel')}
          <input
            className={styles.input}
            value={form.publicPhone}
            onChange={(e) => set('publicPhone', e.target.value)}
            maxLength={30}
          />
        </label>
      </div>

      <div className={styles.formRow}>
        <label className={styles.label}>
          {t('partners.formWebsiteLabel')}
          <input
            className={styles.input}
            type="url"
            value={form.publicWebsiteUrl}
            onChange={(e) => set('publicWebsiteUrl', e.target.value)}
            maxLength={500}
          />
        </label>
      </div>

      {saveError !== null && <p className={styles.errorText}>{saveError}</p>}

      <div className={styles.formActions}>
        <button type="submit" className={styles.btnPrimary} disabled={isSaving}>
          {isSaving ? t('partners.loading') : t('partners.saveCompany')}
        </button>
      </div>
    </form>
  );
};
