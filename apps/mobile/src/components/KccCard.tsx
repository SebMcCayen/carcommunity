import { ReactNode } from 'react';
import { StyleSheet, Text, View } from 'react-native';

import { useAppTheme } from '../hooks/useAppTheme';

type KccCardProps = {
  title: string;
  body: string;
  footer?: ReactNode;
};

export const KccCard = ({ title, body, footer }: KccCardProps) => {
  const { theme } = useAppTheme();

  return (
    <View
      style={[
        styles.card,
        {
          backgroundColor: theme.colors.surfaceBackground,
          borderColor: theme.colors.borderDefault,
          borderRadius: theme.radius.lg,
          padding: theme.spacing[4],
          gap: theme.spacing[3]
        }
      ]}
    >
      <Text style={[styles.title, { color: theme.colors.textPrimary }]}>{title}</Text>
      <Text style={[styles.body, { color: theme.colors.textSecondary }]}>{body}</Text>
      {footer}
    </View>
  );
};

const styles = StyleSheet.create({
  card: {
    borderWidth: 1
  },
  title: {
    fontSize: 18,
    fontWeight: '600'
  },
  body: {
    fontSize: 14,
    lineHeight: 20
  }
});
