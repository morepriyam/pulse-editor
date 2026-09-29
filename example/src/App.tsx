import { useState } from 'react';
import { Button, ScrollView, StyleSheet, Text, TextInput } from 'react-native';
import { probe } from '@mieweb/pulse-editor';

export default function App() {
  const [uri, setUri] = useState('');
  const [output, setOutput] = useState(
    'Enter a local video path or file:// URI'
  );

  const run = async () => {
    const started = Date.now();
    try {
      const result = await probe(uri);
      setOutput(
        `${Date.now() - started} ms\n${JSON.stringify(result, null, 2)}`
      );
    } catch (e) {
      setOutput(String(e));
    }
  };

  return (
    <ScrollView contentContainerStyle={styles.container}>
      <TextInput
        style={styles.input}
        value={uri}
        onChangeText={setUri}
        placeholder="file:///…/clip.mp4"
        autoCapitalize="none"
      />
      <Button title="Probe" onPress={run} />
      <Text style={styles.output}>{output}</Text>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { padding: 24, paddingTop: 80, gap: 12 },
  input: { borderWidth: 1, borderColor: '#999', borderRadius: 6, padding: 8 },
  output: { fontFamily: 'Courier', fontSize: 12 },
});
