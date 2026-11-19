import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  getCompany,
  getMembers,
  setMembership,
  updateProfile,
  uploadLogo,
  logoFileBase64,
  type PortalCompany,
  type PortalMember,
} from '@/features/partners/portal';
import {
  adminActivatePartner,
  adminPausePartner,
  adminEndPartnership,
  adminUpdatePartnerCompany,
} from '@/features/partners';
import { ActionDialog } from '@/components/ui/ActionDialog';
import { MapLocationPicker } from '@/components/map/MapLocationPicker';
import { p, PortalHeader, PartnerPreview, Status, ErrorNotice } from '../portal-components';
import styles from '../portal.module.css';
export default function PartnerProfilePage() {
  const { partnerId } = useParams<{ partnerId: string }>();
  const [company, setCompany] = useState<PortalCompany | null>(null);
  const [members, setMembers] = useState<PortalMember[]>([]);
  const [description, setDescription] = useState('');
  const [phone, setPhone] = useState('');
  const [website, setWebsite] = useState('');
  const [name, setName] = useState('');
  const [address, setAddress] = useState('');
  const [latitude, setLatitude] = useState('');
  const [longitude, setLongitude] = useState('');
  const [uid, setUid] = useState('');
  const [logoRights, setLogoRights] = useState(false);
  const [role, setRole] = useState<PortalMember['role']>('manager');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [action, setAction] = useState<'activate' | 'pause' | 'end' | null>(null);
  const [reason, setReason] = useState('');
  const flight = useRef(false);
  const generation = useRef(0);
  const load = useCallback(async () => {
    if (!partnerId) return;
    const run = ++generation.current;
    setLoading(true);
    setError(null);
    try {
      const [result, staff] = await Promise.all([getCompany(partnerId), getMembers(partnerId)]);
      if (run !== generation.current) return;
      setCompany(result);
      setLogoRights(false);
      setMembers(staff);
      setDescription(result.publicDescription);
      setPhone(result.publicPhone ?? '');
      setWebsite(result.publicWebsiteUrl ?? '');
      setName(result.companyName);
      setAddress(result.address);
      setLatitude(String(result.latitude));
      setLongitude(String(result.longitude));
    } catch (err) {
      if (run === generation.current) setError(err instanceof Error ? err.message : p('error'));
    } finally {
      if (run === generation.current) setLoading(false);
    }
  }, [partnerId]);
  const invalidate = useCallback(() => {
    generation.current++;
  }, []);
  useEffect(() => {
    void load();
    return invalidate;
  }, [load, invalidate]);
  async function mutate(work: () => Promise<unknown>) {
    if (flight.current) return false;
    flight.current = true;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await work();
      setNotice(p('saved'));
      await load();
      return true;
    } catch (err) {
      setError(err instanceof Error ? err.message : p('error'));
      return false;
    } finally {
      setBusy(false);
      flight.current = false;
    }
  }
  if (loading && !company) return <p role="status">{p('loading')}</p>;
  if (!company || !partnerId)
    return <ErrorNotice error={error ?? p('error')} retry={() => void load()} />;
  const editableLocation = company.status === 'draft' || company.status === 'paused';
  return (
    <div className={styles.page}>
      <PortalHeader title={company.companyName} description={p('profileTitle')} />
      <div className={styles.toolbar}>
        <Status value={company.status} />
        <Link
          className={styles.secondary}
          to={`/partners/offers?partnerId=${encodeURIComponent(partnerId)}`}
        >
          {p('viewOffers')}
        </Link>
        <Link
          className={styles.secondary}
          to={`/partners/statistics?partnerId=${encodeURIComponent(partnerId)}`}
        >
          {p('statistics')}
        </Link>
        <Link
          className={styles.secondary}
          to={`/billboards?partnerCompanyId=${encodeURIComponent(partnerId)}`}
        >
          {p('billboards')}
        </Link>
      </div>
      <ErrorNotice error={error} />
      {notice && (
        <p role="status" className={styles.alert}>
          {notice}
        </p>
      )}
      <div className={styles.grid}>
        <div>
          <section className={styles.section}>
            <h2>{p('companyData')}</h2>
            <form
              onSubmit={(event) => {
                event.preventDefault();
                void mutate(async () => {
                  if (editableLocation) {
                    await adminUpdatePartnerCompany(partnerId, {
                      companyName: name.trim(),
                      address: address.trim(),
                      latitude: Number(latitude),
                      longitude: Number(longitude),
                      publicDescription: description.trim(),
                      publicPhone: phone.trim() || null,
                      publicWebsiteUrl: website.trim() || null,
                    });
                  } else {
                    await updateProfile(
                      partnerId,
                      description.trim(),
                      phone.trim() || null,
                      website.trim() || null,
                    );
                  }
                });
              }}
            >
              <div className={styles.formGrid}>
                <label className={styles.field}>
                  {p('name')}
                  <input
                    value={name}
                    disabled={!editableLocation || busy}
                    maxLength={150}
                    required
                    onChange={(event) => setName(event.target.value)}
                  />
                </label>
                <label className={styles.field}>
                  {p('address')}
                  <input
                    value={address}
                    disabled={!editableLocation || busy}
                    maxLength={300}
                    required
                    onChange={(event) => setAddress(event.target.value)}
                  />
                </label>
                <label className={`${styles.field} ${styles.full}`}>
                  {p('description')}
                  <textarea
                    value={description}
                    disabled={busy || company.status === 'ended'}
                    maxLength={1000}
                    required
                    onChange={(event) => setDescription(event.target.value)}
                  />
                </label>
                <label className={styles.field}>
                  {p('publicPhone')}
                  <input
                    value={phone}
                    disabled={busy || company.status === 'ended'}
                    maxLength={30}
                    onChange={(event) => setPhone(event.target.value)}
                  />
                </label>
                <label className={styles.field}>
                  {p('website')}
                  <input
                    value={website}
                    disabled={busy || company.status === 'ended'}
                    maxLength={500}
                    type="url"
                    onChange={(event) => setWebsite(event.target.value)}
                  />
                </label>
                {editableLocation && (
                  <div className={styles.full}>
                    <MapLocationPicker
                      latitude={latitude}
                      longitude={longitude}
                      onChange={(lat, lng) => {
                        setLatitude(lat);
                        setLongitude(lng);
                      }}
                      labelLat={p('latitude')}
                      labelLng={p('longitude')}
                      unavailableText={p('mapUnavailable')}
                      loadErrorText={p('mapLoadError')}
                      required
                      disabled={busy}
                    />
                  </div>
                )}
              </div>
              {!editableLocation && <p className={styles.muted}>{p('profileActiveNote')}</p>}
              <button className={styles.button} disabled={busy || company.status === 'ended'}>
                {p('saveProfile')}
              </button>
            </form>
            <label className={styles.check} style={{ marginTop: 22 }}>
              <input
                type="checkbox"
                checked={logoRights}
                disabled={busy}
                onChange={(event) => setLogoRights(event.target.checked)}
              />
              {p('logoRights')}
            </label>
            <label className={styles.field} style={{ marginTop: 12 }}>
              {p('logo')}
              <input
                type="file"
                accept="image/png"
                disabled={busy || company.status === 'ended' || !logoRights}
                onChange={(event) => {
                  const file = event.target.files?.[0];
                  if (file)
                    void mutate(async () => uploadLogo(partnerId, await logoFileBase64(file)));
                  event.target.value = '';
                }}
              />
            </label>
            <p className={styles.muted}>{p('fileGuidance')}</p>
          </section>
          <section className={styles.section}>
            <h2>{p('managePermission')}</h2>
            <p className={styles.muted}>{p('permissionNote')}</p>
            <form
              className={styles.toolbar}
              onSubmit={(event) => {
                event.preventDefault();
                void mutate(() => setMembership(partnerId, uid.trim(), role, true)).then(
                  (saved) => {
                    if (saved) setUid('');
                  },
                );
              }}
            >
              <label className={styles.field}>
                {p('userId')}
                <input
                  required
                  value={uid}
                  maxLength={128}
                  disabled={busy}
                  onChange={(event) => setUid(event.target.value)}
                />
              </label>
              <label className={styles.field}>
                {p('role')}
                <select
                  value={role}
                  disabled={busy}
                  onChange={(event) => setRole(event.target.value as PortalMember['role'])}
                >
                  <option value="manager">{p('editor')}</option>
                  <option value="redeemer">{p('redeemer')}</option>
                </select>
              </label>
              <button className={styles.button} disabled={busy || !uid.trim()}>
                {p('grant')}
              </button>
            </form>
            {members.length ? (
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th>{p('userId')}</th>
                    <th>{p('role')}</th>
                    <th>{p('actions')}</th>
                  </tr>
                </thead>
                <tbody>
                  {members.map((member) => (
                    <tr key={member.uid}>
                      <td>{member.uid}</td>
                      <td>
                        {p(member.role === 'manager' ? 'editor' : 'redeemer')} ·{' '}
                        {p(member.enabled ? 'active' : 'paused')}
                      </td>
                      <td>
                        <button
                          className={styles.linkButton}
                          disabled={busy}
                          onClick={() =>
                            void mutate(() =>
                              setMembership(partnerId, member.uid, member.role, !member.enabled),
                            )
                          }
                        >
                          {p(member.enabled ? 'revoke' : 'grant')}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : (
              <p>{p('noPermission')}</p>
            )}
          </section>
          <section className={styles.section}>
            <div className={styles.actions}>
              {company.status !== 'ended' && (
                <>
                  {company.status === 'active' ? (
                    <button
                      className={styles.secondary}
                      disabled={busy}
                      onClick={() => {
                        setReason('');
                        setAction('pause');
                      }}
                    >
                      {p('pause')}
                    </button>
                  ) : (
                    <button
                      className={styles.button}
                      disabled={busy}
                      onClick={() => {
                        setReason('');
                        setAction('activate');
                      }}
                    >
                      {p('activate')}
                    </button>
                  )}
                  <button
                    className={styles.danger}
                    disabled={busy}
                    onClick={() => {
                      setReason('');
                      setAction('end');
                    }}
                  >
                    {p('end')}
                  </button>
                </>
              )}
            </div>
          </section>
        </div>
        <PartnerPreview
          name={name}
          category={company.category}
          description={description}
          address={address}
          phone={phone}
          website={website}
          logoPath={company.logoPath}
        />
      </div>
      {action && (
        <ActionDialog
          title={p('confirmAction')}
          onCancel={() => setAction(null)}
          cancelDisabled={busy}
        >
          <form
            onSubmit={(event) => {
              event.preventDefault();
              const selected = action;
              void mutate(async () => {
                if (selected === 'activate') await adminActivatePartner(partnerId);
                else if (selected === 'pause') await adminPausePartner(partnerId, reason.trim());
                else await adminEndPartnership(partnerId, reason.trim());
                setAction(null);
              });
            }}
          >
            {action !== 'activate' && (
              <label className={styles.field}>
                {p('reason')}
                <textarea
                  required
                  minLength={3}
                  maxLength={2000}
                  value={reason}
                  onChange={(event) => setReason(event.target.value)}
                />
              </label>
            )}
            <button
              className={styles.button}
              disabled={busy || (action !== 'activate' && reason.trim().length < 3)}
            >
              {p('confirm')}
            </button>
          </form>
        </ActionDialog>
      )}
    </div>
  );
}
