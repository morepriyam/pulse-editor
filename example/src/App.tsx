import { Text, View, StyleSheet } from 'react-native';
import { hello } from 'pulse-editor';

const result = hello();

export default function App() {
  return (
    <View style={styles.container}>
      <Text>{result}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
});
