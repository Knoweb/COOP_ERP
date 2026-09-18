import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useT } from '../../shell/i18n/useT';
import type { components } from '../../generated/hello';

type GreetingResponse = components['schemas']['GreetingResponse'];
type RegisterGreetingRequest = components['schemas']['RegisterGreetingRequest'];

export function HelloPage() {
  const t = useT();
  const queryClient = useQueryClient();
  const [textEn, setTextEn] = useState('');

  const apiBase = import.meta.env.VITE_API_BASE || 'http://localhost:8080';

  // Fetch list of greetings. Note: backend spec only defines GET /{id}, 
  // so we fallback gracefully to empty array if the list endpoint 404s.
  const { data: greetings = [], isLoading } = useQuery({
    queryKey: ['greetings'],
    queryFn: async (): Promise<GreetingResponse[]> => {
      const res = await fetch(`${apiBase}/v1/hello/greetings`);
      if (!res.ok) {
        if (res.status === 404) return [];
        throw new Error('Failed to fetch greetings');
      }
      return res.json();
    }
  });

  const mutation = useMutation({
    mutationFn: async (newGreeting: RegisterGreetingRequest) => {
      const res = await fetch(`${apiBase}/v1/hello/greetings`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': crypto.randomUUID() // Required by spec
        },
        body: JSON.stringify(newGreeting)
      });
      if (!res.ok) throw new Error('Failed to submit greeting');
      return res.json() as Promise<GreetingResponse>;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['greetings'] });
      setTextEn('');
    }
  });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!textEn.trim()) return;
    mutation.mutate({ textEn });
  };

  return (
    <div style={{ padding: '2rem', maxWidth: '600px', margin: '0 auto' }}>
      <h1>{t('hello_world').text}</h1>
      
      <form onSubmit={handleSubmit} style={{ marginBottom: '2rem', display: 'flex', gap: '1rem' }}>
        <input 
          type="text" 
          value={textEn} 
          onChange={(e) => setTextEn(e.target.value)} 
          placeholder="Enter english greeting"
          disabled={mutation.isPending}
          style={{ flex: 1, padding: '0.5rem' }}
        />
        <button type="submit" disabled={mutation.isPending || !textEn.trim()} style={{ padding: '0.5rem 1rem' }}>
          {mutation.isPending ? 'Submitting...' : 'Add Greeting'}
        </button>
      </form>

      <div className="greetings-list">
        <h2>Submitted Greetings</h2>
        {isLoading ? (
          <p>Loading...</p>
        ) : greetings.length === 0 ? (
          <p>No greetings found.</p>
        ) : (
          <ul style={{ listStyle: 'none', padding: 0 }}>
            {greetings.map((g, idx) => (
              <li key={g.id || idx} style={{ padding: '1rem', border: '1px solid #ccc', marginBottom: '0.5rem', borderRadius: '4px' }}>
                <strong>{g.textEn}</strong> <span style={{ color: 'gray', fontSize: '0.8rem' }}>(Status: {g.status})</span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
