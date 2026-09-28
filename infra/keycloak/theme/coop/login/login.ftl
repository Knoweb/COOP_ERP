<!DOCTYPE html>
<html lang="${locale.currentLanguageTag}">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${msg("loginTitle",(realm.displayName!''))}</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap" rel="stylesheet">
    <link href="${url.resourcesPath}/css/styles.css" rel="stylesheet" />
</head>
<body class="login-pf-page">

<#if realm.internationalizationEnabled  && locale.supported?size gt 1>
    <div class="language-position">
        <div class="language-wrapper" id="language-wrapper">
            <button class="language-selector" type="button" onclick="toggleLanguageDropdown(event)">
                <svg class="globe-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                    <circle cx="12" cy="12" r="9" />
                    <path d="M3 12h18" />
                    <path d="M12 3c2.5 2.5 4 5.5 4 9s-1.5 6.5-4 9" />
                    <path d="M12 3c-2.5 2.5-4 5.5-4 9s1.5 6.5 4 9" />
                </svg>
                <span>${locale.current}</span>
                <svg class="arrow-icon" id="language-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="m6 9 6 6 6-6" />
                </svg>
            </button>

            <div class="language-dropdown" id="language-dropdown" style="display: none;">
                <#list locale.supported as l>
                    <button type="button" class="<#if l.label == locale.current>active-language</#if>" onclick="location.href='${l.url}';">
                        ${l.label}
                    </button>
                </#list>
            </div>
        </div>
    </div>
</#if>

<div class="login-card">
    <!-- Logo -->
    <div class="brand">
        <img src="${url.resourcesPath}/img/coop_logo-removebg-preview.png" alt="COOP Logo" class="coopfed-logo" />
        <h1>
            <span class="coopfed">COOPFED</span> <span class="erp">ERP</span>
        </h1>
        <p>Cooperative Federation Enterprise Resource Planning</p>
    </div>

    <!-- Heading -->
    <div class="welcome">
        <h2>Welcome Back</h2>
        <p>Sign in to access your account</p>
    </div>

    <!-- Alerts -->
    <#if message?has_content && (message.type != 'warning' || !isAppInitiatedAction??)>
        <div class="alert alert-${message.type}">
            <span>${kcSanitize(message.summary)?no_esc}</span>
        </div>
    </#if>

    <form id="kc-form-login" onsubmit="login.disabled = true; return true;" action="${url.loginAction}" method="post">
        <!-- Username -->
        <div class="input-group">
            <svg class="input-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                <path d="M20 21a8 8 0 0 0-16 0" />
                <circle cx="12" cy="7" r="4" />
            </svg>
            <input tabindex="1" id="username" class="pf-c-form-control" name="username" value="${(login.username!'')}" type="text" autofocus autocomplete="username" placeholder="Username" />
        </div>

        <!-- Password -->
        <div class="input-group">
            <svg class="input-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                <rect x="4" y="10" width="16" height="11" rx="2" />
                <path d="M8 10V7a4 4 0 0 1 8 0v3" />
            </svg>
            <input tabindex="2" id="password" class="pf-c-form-control" name="password" type="password" autocomplete="current-password" placeholder="Password" />
            
            <button type="button" class="eye-button" onclick="togglePassword()" aria-label="Show or hide password">
                <svg id="eye-icon-show" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" style="display:none;">
                    <path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12Z" />
                    <circle cx="12" cy="12" r="3" />
                </svg>
                <svg id="eye-icon-hide" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                    <path d="M3 3l18 18" />
                    <path d="M10.6 10.6a2 2 0 0 0 2.8 2.8" />
                    <path d="M9.9 4.2A10.7 10.7 0 0 1 12 4c6.5 0 10 8 10 8a18 18 0 0 1-3 4.2" />
                    <path d="M6.6 6.6C3.6 8.6 2 12 2 12s3.5 8 10 8a9.8 9.8 0 0 0 4-.8" />
                </svg>
            </button>
        </div>

        <!-- Options -->
        <div class="login-options">
            <#if realm.rememberMe && !usernameHidden??>
                <label class="remember">
                    <#if login.rememberMe??>
                        <input tabindex="3" id="rememberMe" name="rememberMe" type="checkbox" checked>
                    <#else>
                        <input tabindex="3" id="rememberMe" name="rememberMe" type="checkbox">
                    </#if>
                    <span>Remember me</span>
                </label>
            </#if>
            <#if realm.resetPasswordAllowed>
                <a tabindex="5" href="${url.loginResetCredentialsUrl}">Forgot password?</a>
            </#if>
        </div>

        <!-- Sign In -->
        <button tabindex="4" name="login" id="kc-login" type="submit" class="sign-in-button">
            <span>Sign In</span>
            <span class="arrow">→</span>
        </button>
    </form>

    <!-- Decorative leaf -->
    <div class="decorative-leaf">
        <span></span>
        <span></span>
        <span></span>
    </div>
</div>

<script>
    function togglePassword() {
        var x = document.getElementById("password");
        var showIcon = document.getElementById("eye-icon-show");
        var hideIcon = document.getElementById("eye-icon-hide");
        if (x.type === "password") {
            x.type = "text";
            showIcon.style.display = "block";
            hideIcon.style.display = "none";
        } else {
            x.type = "password";
            showIcon.style.display = "none";
            hideIcon.style.display = "block";
        }
    }

    function toggleLanguageDropdown(event) {
        event.stopPropagation();
        var dropdown = document.getElementById('language-dropdown');
        var arrow = document.getElementById('language-arrow');
        if (dropdown.style.display === 'none' || dropdown.style.display === '') {
            dropdown.style.display = 'block';
            arrow.classList.add('rotate');
        } else {
            dropdown.style.display = 'none';
            arrow.classList.remove('rotate');
        }
    }

    document.addEventListener('click', function(event) {
        var wrapper = document.getElementById('language-wrapper');
        if (wrapper && !wrapper.contains(event.target)) {
            var dropdown = document.getElementById('language-dropdown');
            var arrow = document.getElementById('language-arrow');
            if (dropdown && dropdown.style.display === 'block') {
                dropdown.style.display = 'none';
                arrow.classList.remove('rotate');
            }
        }
    });
</script>

</body>
</html>
