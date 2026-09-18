package lk.coopfed.knoweb.hello.internal;

import lk.coopfed.knoweb.hello.api.RegisterGreeting;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

// Assuming @CommandHandler and Handles are in kernel.api
// import lk.coopfed.knoweb.kernel.api.CommandHandler;
// import lk.coopfed.knoweb.kernel.api.Handles;

import org.springframework.stereotype.Service;

// @CommandHandler(permission = "hello.greeting.register")
@Service
public class RegisterGreetingHandler /* implements Handles<RegisterGreeting, UUID> */ {
    
    @Transactional
    public UUID handle(RegisterGreeting cmd, ScopeContext scope) {
        // 1. guards
        
        // 2. mutation
        
        // 3. audit.record (blocked by 19A)
        
        // 4. events.publish (blocked by 19A)
        
        return UUID.randomUUID();
    }
}
