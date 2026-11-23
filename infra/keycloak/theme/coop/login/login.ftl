<#--
  The COOP ERP sign-in page (wave 2, DEPLOY-16 to DEPLOY-18).
  - Every text is a message id, ${msg("...")}, with its English, Sinhala and Tamil texts in
    messages/messages_{en,si,ta}.properties: the custom ids (coop*) and the Keycloak ids this page
    shows, because Keycloak ships no Sinhala bundle. tools/check-i18n.mjs fails the build when
    the three files do not hold the same ids.
  - No inline event handlers: the languages are plain links (the escaping of an href is right,
    that of a URL inside a JavaScript string in an attribute is not), and the two scripts below
    attach their own listeners.
  - No third-party request: the fonts are this theme's own (resources/fonts).
-->
<!DOCTYPE html>
<html lang="${locale.currentLanguageTag}">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${msg("loginTitle",(realm.displayName!''))}</title>
    <link href="${url.resourcesPath}/css/styles.css?v=16" rel="stylesheet" />
</head>
<body class="auth-page">

<div class="auth-layout">
    <!-- Left Showcase Side (Border-free Floating Typography & Metrics) -->
    <div class="showcase-side" aria-hidden="true">
        <div class="showcase-overlay"></div>
        <div class="showcase-content">
            <div class="showcase-hero">
                <div class="showcase-badge">
                    <span class="badge-dot"></span>
                    <span>COOPFED SRI LANKA</span>
                </div>

                <h1 class="showcase-title">${msg("coopHeroHeadline")}</h1>
                <p class="showcase-desc">${msg("coopHeroSub")}</p>

                <!-- Modern Borderless Stat Strip -->
                <div class="showcase-metrics">
                    <div class="metric-item">
                        <span class="metric-val">800+</span>
                        <span class="metric-lbl">${msg("coopStatSocieties")}</span>
                    </div>
                    <div class="metric-divider"></div>
                    <div class="metric-item">
                        <span class="metric-val">4,000+</span>
                        <span class="metric-lbl">${msg("coopStatOutlets")}</span>
                    </div>
                    <div class="metric-divider"></div>
                    <div class="metric-item">
                        <span class="metric-val">100%</span>
                        <span class="metric-lbl">${msg("coopStatSync")}</span>
                    </div>
                </div>

                <div class="showcase-trust">
                    <svg class="shield-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                        <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
                        <path d="m9 12 2 2 4-4" />
                    </svg>
                    <span>${msg("coopSecurityBadge")}</span>
                </div>
            </div>
        </div>
    </div>

    <!-- Right Portal / Form Side -->
    <div class="portal-side">
        <!-- Top Navigation Bar inside form panel -->
        <div class="portal-topbar">
            <div class="mobile-brand-pill">
                <span class="coopfed-text">COOPFED</span> <span class="erp-text">ERP</span>
            </div>
            <#if realm.internationalizationEnabled  && locale.supported?size gt 1>
                <div class="language-position">
                    <details class="language-wrapper" id="language-wrapper">
                        <summary class="language-selector" aria-label="${msg("coopChooseLanguage")}">
                            <svg class="globe-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
                                <circle cx="12" cy="12" r="9" />
                                <path d="M3 12h18" />
                                <path d="M12 3c2.5 2.5 4 5.5 4 9s-1.5 6.5-4 9" />
                                <path d="M12 3c-2.5 2.5-4 5.5-4 9s1.5 6.5 4 9" />
                            </svg>
                            <span>${locale.current}</span>
                            <svg class="arrow-icon" id="language-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
                                <path d="m6 9 6 6 6-6" />
                            </svg>
                        </summary>

                        <div class="language-dropdown" id="language-dropdown">
                            <#list locale.supported as l>
                                <a href="${l.url}" class="<#if l.label == locale.current>active-language</#if>">${l.label}</a>
                            </#list>
                        </div>
                    </details>
                </div>
            </#if>
        </div>

        <!-- Form Card / Container -->
        <div class="portal-main">
            <div class="portal-card">
                <!-- Brand Emblem -->
                <div class="brand">
                    <div class="logo-wrapper">
                        <img src="${url.resourcesPath}/img/coop_logo-removebg-preview.png" alt="${msg("coopLogoAlt")}" class="coopfed-logo" />
                    </div>
                    <h1>
                        <span class="coopfed">COOPFED</span> <span class="erp">ERP</span>
                    </h1>
                    <p class="tagline">${msg("coopTagline")}</p>
                </div>

                <!-- Welcome Text -->
                <div class="welcome">
                    <h2>${msg("coopWelcome")}</h2>
                    <p>${msg("coopSignInPrompt")}</p>
                </div>

                <!-- Alerts -->
                <#if message?has_content && (message.type != 'warning' || !isAppInitiatedAction??)>
                    <div class="alert alert-${message.type}" role="alert">
                        <span>${kcSanitize(message.summary)?no_esc}</span>
                    </div>
                </#if>

                <!-- Login Form -->
                <form id="kc-form-login" action="${url.loginAction}" method="post">
                    <!-- Username -->
                    <div class="form-field">
                        <label for="username" class="field-label">${msg("username")}</label>
                        <div class="input-group">
                            <svg class="input-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
                                <path d="M20 21a8 8 0 0 0-16 0" />
                                <circle cx="12" cy="7" r="4" />
                            </svg>
                            <input tabindex="1" id="username" class="pf-c-form-control" name="username" value="${(login.username!'')}" type="text" autofocus autocomplete="username" placeholder="${msg("username")}" aria-label="${msg("username")}" />
                        </div>
                    </div>

                    <!-- Password -->
                    <div class="form-field">
                        <div class="field-label-row">
                            <label for="password" class="field-label">${msg("password")}</label>
                            <#if realm.resetPasswordAllowed>
                                <a tabindex="5" href="${url.loginResetCredentialsUrl}" class="forgot-link">${msg("doForgotPassword")}</a>
                            </#if>
                        </div>
                        <div class="input-group">
                            <svg class="input-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
                                <rect x="4" y="10" width="16" height="11" rx="2" />
                                <path d="M8 10V7a4 4 0 0 1 8 0v3" />
                            </svg>
                            <input tabindex="2" id="password" class="pf-c-form-control" name="password" type="password" autocomplete="current-password" placeholder="${msg("password")}" aria-label="${msg("password")}" />

                            <button type="button" class="eye-button" id="password-toggle" aria-label="${msg("coopShowHidePassword")}">
                                <svg id="eye-icon-show" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" style="display:none;" aria-hidden="true">
                                    <path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12Z" />
                                    <circle cx="12" cy="12" r="3" />
                                </svg>
                                <svg id="eye-icon-hide" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
                                    <path d="M3 3l18 18" />
                                    <path d="M10.6 10.6a2 2 0 0 0 2.8 2.8" />
                                    <path d="M9.9 4.2A10.7 10.7 0 0 1 12 4c6.5 0 10 8 10 8a18 18 0 0 1-3 4.2" />
                                    <path d="M6.6 6.6C3.6 8.6 2 12 2 12s3.5 8 10 8a9.8 9.8 0 0 0 4-.8" />
                                </svg>
                            </button>
                        </div>
                    </div>

                    <!-- Options -->
                    <#if realm.rememberMe && !usernameHidden??>
                        <div class="login-options">
                            <label class="remember">
                                <#if login.rememberMe??>
                                    <input tabindex="3" id="rememberMe" name="rememberMe" type="checkbox" checked>
                                <#else>
                                    <input tabindex="3" id="rememberMe" name="rememberMe" type="checkbox">
                                </#if>
                                <span>${msg("rememberMe")}</span>
                            </label>
                        </div>
                    </#if>

                    <!-- Sign In -->
                    <button tabindex="4" name="login" id="kc-login" type="submit" class="sign-in-button">
                        <span>${msg("doLogIn")}</span>
                        <svg class="btn-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true">
                            <path d="M5 12h14" />
                            <path d="m12 5 7 7-7 7" />
                        </svg>
                    </button>
                </form>
            </div>
        </div>

        <!-- Footer -->
        <div class="portal-footer">
            <p>${msg("coopCopyright")}</p>
        </div>
    </div>
</div>

<script>
    (function () {
        var form = document.getElementById("kc-form-login");
        var login = document.getElementById("kc-login");
        if (form && login) {
            form.addEventListener("submit", function () { login.disabled = true; });
        }

        var toggle = document.getElementById("password-toggle");
        if (toggle) {
            toggle.addEventListener("click", function () {
                var x = document.getElementById("password");
                var showIcon = document.getElementById("eye-icon-show");
                var hideIcon = document.getElementById("eye-icon-hide");
                var hidden = x.type === "password";
                x.type = hidden ? "text" : "password";
                showIcon.style.display = hidden ? "block" : "none";
                hideIcon.style.display = hidden ? "none" : "block";
            });
        }

        // The language picker closes when the user clicks elsewhere.
        document.addEventListener("click", function (event) {
            var wrapper = document.getElementById("language-wrapper");
            if (wrapper && wrapper.open && !wrapper.contains(event.target)) {
                wrapper.open = false;
            }
        });
    })();
</script>

</body>
</html>
