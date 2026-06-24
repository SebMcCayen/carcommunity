/**
 * PointsWalletScreen — Kronpoäng (KP) private wallet.
 *
 * Shows the current user's KP balance and recent transaction history.
 *
 * Privacy rules:
 *  - Only shows the current user's own balance — never other users'.
 *  - Wallet data is cleared when the hook unmounts (e.g. on logout).
 *  - No rankings, comparisons, or other users' balances.
 *  - Backend balance is used as authoritative — never sum local transactions.
 *  - No purchase, transfer, withdrawal, or cash-value controls.
 *  - No points are awarded from the client.
 *
 * Accessibility:
 *  - All interactive elements have accessibilityRole and accessibilityLabel.
 *  - Text uses readable contrast via design tokens.
 */

import {
  ActivityIndicator,
  FlatList,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';

import type { PointsTransactionSummary } from '@carcommunity/shared/points';

import { useAppTheme } from '../hooks/useAppTheme';
import { usePoints } from '../hooks/usePoints';
import { useI18n } from '../hooks/useI18n';
import { KccButton } from '../components/KccButton';
import { ScreenContainer } from '../components/ScreenContainer';

// ---------------------------------------------------------------------------
// Transaction row
// ---------------------------------------------------------------------------

interface TransactionRowProps {
  tx: PointsTransactionSummary;
}

const TransactionRow = ({ tx }: TransactionRowProps) => {
  const { theme } = useAppTheme();

  const isCredit = tx.amount > 0;
  const amountLabel = isCredit ? `+${tx.amount} KP` : `${tx.amount} KP`;
  const amountColor = isCredit ? theme.colors.statusSuccess : theme.colors.statusError;

  const date = new Date(tx.createdAt).toLocaleDateString('sv-SE', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  });

  return (
    <View
      accessibilityRole="text"
      style={[
        styles.row,
        {
          borderBottomColor: theme.colors.borderDefault,
          paddingVertical: theme.spacing[3],
        },
      ]}
    >
      <View style={styles.rowLeft}>
        <Text
          style={[
            styles.rowDesc,
            { color: theme.colors.textPrimary, fontSize: theme.typography.size.bodySm },
          ]}
          numberOfLines={2}
        >
          {tx.description}
        </Text>
        <Text
          style={[
            styles.rowDate,
            { color: theme.colors.textSecondary, fontSize: theme.typography.size.caption },
          ]}
        >
          {date}
        </Text>
      </View>
      <Text
        style={[
          styles.rowAmount,
          {
            color: amountColor,
            fontSize: theme.typography.size.bodyMd,
            fontWeight: theme.typography.weight.semibold,
          },
        ]}
      >
        {amountLabel}
      </Text>
    </View>
  );
};

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

export const PointsWalletScreen = () => {
  const { t } = useI18n();
  const { theme } = useAppTheme();
  const {
    balance,
    transactions,
    isLoading,
    error,
    hasMore,
    refresh,
    loadMore,
  } = usePoints();

  return (
    <ScreenContainer>
      {/* Balance card */}
      <View
        style={[
          styles.balanceCard,
          {
            backgroundColor: theme.colors.surfaceBackground,
            borderColor: theme.colors.borderDefault,
            borderRadius: theme.radius.lg,
            padding: theme.spacing[5],
            marginBottom: theme.spacing[5],
          },
        ]}
      >
        <Text
          accessibilityRole="header"
          style={[
            styles.balanceTitle,
            {
              color: theme.colors.textSecondary,
              fontSize: theme.typography.size.bodySm,
              marginBottom: theme.spacing[1],
            },
          ]}
        >
          {t('points.balanceLabel')}
        </Text>
        <Text
          accessibilityRole="text"
          style={[
            styles.balanceValue,
            {
              color: theme.colors.textPrimary,
              fontSize: theme.typography.size.headingLg,
              fontWeight: theme.typography.weight.semibold,
            },
          ]}
        >
          {balance} {t('points.shortForm')}
        </Text>
        <Text
          style={[
            styles.balanceDisclaimer,
            {
              color: theme.colors.textSecondary,
              fontSize: theme.typography.size.caption,
              marginTop: theme.spacing[3],
            },
          ]}
        >
          {t('points.disclaimer')}
        </Text>
        <Text
          style={[
            styles.balanceDisclaimer,
            {
              color: theme.colors.textSecondary,
              fontSize: theme.typography.size.caption,
              marginTop: theme.spacing[1],
            },
          ]}
        >
          {t('points.noTransfer')}
        </Text>
      </View>

      {/* Transactions section */}
      <Text
        accessibilityRole="header"
        style={[
          styles.sectionTitle,
          {
            color: theme.colors.textPrimary,
            fontSize: theme.typography.size.titleMd,
            fontWeight: theme.typography.weight.semibold,
            marginBottom: theme.spacing[3],
          },
        ]}
      >
        {t('points.recentTransactions')}
      </Text>

      {isLoading && transactions.length === 0 ? (
        <ActivityIndicator
          accessibilityLabel={t('points.loading')}
          color={theme.colors.brandPrimary}
          style={styles.loader}
        />
      ) : error ? (
        <View style={styles.centeredMessage}>
          <Text style={[styles.message, { color: theme.colors.textSecondary }]}>
            {t('points.error')}
          </Text>
          <KccButton label={t('points.retry')} onPress={refresh} variant="secondary" />
        </View>
      ) : transactions.length === 0 ? (
        <View style={styles.centeredMessage}>
          <Text
            accessibilityRole="text"
            style={[
              styles.message,
              { color: theme.colors.textSecondary, fontSize: theme.typography.size.bodyMd },
            ]}
          >
            {t('points.empty')}
          </Text>
        </View>
      ) : (
        <>
          <FlatList
            data={transactions}
            keyExtractor={(item) => item.transactionId}
            renderItem={({ item }) => <TransactionRow tx={item} />}
            scrollEnabled={false}
          />
          {hasMore && (
            <TouchableOpacity
              accessibilityRole="button"
              accessibilityLabel={t('points.loadMore')}
              onPress={() => void loadMore()}
              style={[
                styles.loadMore,
                {
                  paddingVertical: theme.spacing[3],
                  marginTop: theme.spacing[2],
                },
              ]}
            >
              {isLoading ? (
                <ActivityIndicator color={theme.colors.brandPrimary} />
              ) : (
                <Text
                  style={[
                    styles.loadMoreText,
                    { color: theme.colors.brandPrimary, fontSize: theme.typography.size.bodySm },
                  ]}
                >
                  {t('points.loadMore')}
                </Text>
              )}
            </TouchableOpacity>
          )}
        </>
      )}
    </ScreenContainer>
  );
};

// ---------------------------------------------------------------------------
// Styles
// ---------------------------------------------------------------------------

const styles = StyleSheet.create({
  balanceCard: {
    borderWidth: 1,
  },
  balanceTitle: {},
  balanceValue: {},
  balanceDisclaimer: {
    lineHeight: 18,
  },
  sectionTitle: {},
  loader: {
    marginTop: 32,
  },
  centeredMessage: {
    marginTop: 32,
    alignItems: 'center',
  },
  message: {
    textAlign: 'center',
    lineHeight: 22,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  rowLeft: {
    flex: 1,
    marginRight: 12,
  },
  rowDesc: {
    lineHeight: 20,
  },
  rowDate: {
    marginTop: 2,
    lineHeight: 18,
  },
  rowAmount: {},
  loadMore: {
    alignItems: 'center',
  },
  loadMoreText: {},
});
