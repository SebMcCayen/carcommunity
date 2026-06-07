import { ReactNode } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';

import { useAppTheme } from '../hooks/useAppTheme';

type ScreenContainerProps = {
  children: ReactNode;
};

export const ScreenContainer = ({ children }: ScreenContainerProps) => {
  const { theme } = useAppTheme();

  return (
    <ScrollView style={[styles.screen, { backgroundColor: theme.colors.pageBackground }]}> 
      <View style={[styles.content, { gap: theme.spacing[4] }]}>{children}</View>
    </ScrollView>
  );
};

const styles = StyleSheet.create({
  screen: {
    flex: 1
  },
  content: {
    padding: 16,
    paddingBottom: 32
  }
});
