use crate::{
    crypto,
    store::{Actor, Command, Fault, Store},
};
use axum::{
    body::Body,
    extract::{ConnectInfo, DefaultBodyLimit, Path, State},
    http::{HeaderMap, HeaderValue, Request, StatusCode},
    middleware::{self, Next},
    response::{Html, IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use serde::Deserialize;
use serde_json::{json, Value};
use std::{
    net::SocketAddr,
    sync::{Arc, Mutex},
};
use tokio::sync::Semaphore;

#[derive(Clone)]
pub struct App {
    pub store: Arc<Mutex<Store>>,
    pub origin: String,
    pub dev: bool,
    pub device: bool,
    pub gate: Arc<Semaphore>,
    pub requests: Arc<Semaphore>,
    pub admin_allow: Vec<ipnet::IpNet>,
    pub bind_devices: bool,
}
impl IntoResponse for Fault {
    fn into_response(self) -> Response {
        (
            StatusCode::from_u16(self.0).unwrap_or(StatusCode::INTERNAL_SERVER_ERROR),
            Json(json!({"error":self.1})),
        )
            .into_response()
    }
}
async fn blocking<T: Send + 'static>(
    app: App,
    f: impl FnOnce(&mut Store) -> crate::store::Result<T> + Send + 'static,
) -> crate::store::Result<T> {
    let permit = app
        .gate
        .clone()
        .try_acquire_owned()
        .map_err(|_| Fault(503, "Server is busy; retry shortly".into()))?;
    tokio::task::spawn_blocking(move || {
        let _permit = permit;
        let mut store = app
            .store
            .lock()
            .map_err(|_| Fault(500, "Storage unavailable".into()))?;
        f(&mut store)
    })
    .await
    .map_err(|_| Fault(500, "Worker failed".into()))?
}
fn credential(h: &HeaderMap, device: bool) -> crate::store::Result<String> {
    if let Some(b) = h
        .get("authorization")
        .and_then(|x| x.to_str().ok())
        .and_then(|s| s.strip_prefix("Bearer "))
    {
        return Ok(b.into());
    }
    if !device {
        if let Some(t) = h.get("cookie").and_then(|x| x.to_str().ok()).and_then(|s| {
            s.split(';')
                .find_map(|c| c.trim().strip_prefix("field_session="))
        }) {
            return Ok(t.into());
        }
    }
    Err(Fault(401, "Sign in required".into()))
}
fn csrf(token: &str) -> String {
    crypto::digest(format!("csrf:{token}").as_bytes())
}
async fn secure(State(app): State<App>, request: Request<Body>, next: Next) -> Response {
    let path = request.uri().path();
    let bootstrap = matches!(
        path,
        "/api/enrollment/claim" | "/api/enrollment/status" | "/api/enrollment/cancel"
    );
    if bootstrap
        && (request.headers().contains_key("cookie") || request.headers().contains_key("origin"))
    {
        return Fault(
            403,
            "Enrollment must be initiated by the phone through its pinned LAN connection".into(),
        )
        .into_response();
    }
    if app.device
        && app.bind_devices
        && !matches!(path, "/health" | "/api/login" | "/api/device/activate")
    {
        let peer = match request.extensions().get::<ConnectInfo<SocketAddr>>() {
            Some(c) => c.0.ip(),
            None => return Fault(403, "Direct device connection required".into()).into_response(),
        };
        let token = match credential(request.headers(), true) {
            Ok(t) => t,
            Err(e) => return e.into_response(),
        };
        if let Err(e) = blocking(app.clone(), move |s| s.authorize_device(&token, peer)).await {
            return e.into_response();
        }
    }
    if !app.device && !app.dev {
        let remote = request
            .extensions()
            .get::<ConnectInfo<SocketAddr>>()
            .map(|c| c.0.ip());
        if !remote.is_some_and(|ip| app.admin_allow.iter().any(|network| network.contains(&ip))) {
            return Fault(
                403,
                "Management access is restricted to the configured LAN".into(),
            )
            .into_response();
        }
    }
    let _request_slot = match app.requests.clone().try_acquire_owned() {
        Ok(p) => p,
        Err(_) => return Fault(503, "Too many requests; retry shortly".into()).into_response(),
    };
    if request
        .headers()
        .get("x-field-protocol")
        .is_some_and(|h| h.as_bytes() != b"1")
    {
        return Fault(
            426,
            "Unsupported protocol; update the client before synchronizing".into(),
        )
        .into_response();
    }
    if let Some(origin) = request.headers().get("origin") {
        if origin.to_str().ok() != Some(app.origin.as_str()) {
            return Fault(403, "Origin not permitted".into()).into_response();
        }
    }
    let mutation = !matches!(
        *request.method(),
        axum::http::Method::GET | axum::http::Method::HEAD
    );
    let login = request.uri().path() == "/api/login"
        || bootstrap
        || (app.device && request.uri().path() == "/api/device/activate");
    if mutation && !login {
        let native = request.headers().contains_key("authorization");
        if !native {
            let token = match credential(request.headers(), app.device) {
                Ok(t) => t,
                Err(e) => return e.into_response(),
            };
            if request
                .headers()
                .get("x-field-csrf")
                .and_then(|h| h.to_str().ok())
                != Some(csrf(&token).as_str())
            {
                return Fault(403, "Request verification failed".into()).into_response();
            }
        }
    }
    let mut response =
        match tokio::time::timeout(std::time::Duration::from_secs(30), next.run(request)).await {
            Ok(r) => r,
            Err(_) => {
                Fault(504, "Request timed out; retry the same operation ID".into()).into_response()
            }
        };
    let headers = response.headers_mut();
    for (k,v) in [("cache-control","no-store"),("x-content-type-options","nosniff"),("referrer-policy","no-referrer"),("x-frame-options","DENY"),("permissions-policy","camera=(), microphone=(), geolocation=()"),("content-security-policy","default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; connect-src 'self'; font-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'")] {headers.insert(k,HeaderValue::from_static(v));}
    response
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Login {
    name: String,
    password: String,
    device: String,
}
async fn login(
    State(app): State<App>,
    ConnectInfo(addr): ConnectInfo<SocketAddr>,
    Json(input): Json<Login>,
) -> Result<Response, Fault> {
    let source = addr.ip().to_string();
    let device = app.device;
    let bound = device && app.bind_devices;
    let dev = app.dev;
    let (token, actor) = blocking(app, move |s| {
        if bound {
            s.login_enrolled(&input.name, &input.password, &input.device, addr.ip())
        } else {
            s.login(&input.name, &input.password, &input.device, &source)
        }
    })
    .await?;
    let mut response = Json(if device {
        json!({"actor":actor,"token":token})
    } else {
        json!({"actor":actor,"csrf":csrf(&token)})
    })
    .into_response();
    if !device {
        response.headers_mut().insert(
            "set-cookie",
            HeaderValue::from_str(&format!(
                "field_session={token}; HttpOnly; Path=/; SameSite=Strict; Max-Age=2592000{}",
                if dev { "" } else { "; Secure" }
            ))
            .map_err(|_| Fault(500, "Session failed".into()))?,
        );
    }
    Ok(response)
}
async fn session(State(app): State<App>, h: HeaderMap) -> Result<Json<Value>, Fault> {
    let token = credential(&h, app.device)?;
    Ok(Json(
        blocking(app, move |s| {
            Ok(json!({"actor":s.actor(&token)?,"csrf":csrf(&token)}))
        })
        .await?,
    ))
}
async fn logout(State(app): State<App>, h: HeaderMap) -> Result<Response, Fault> {
    let token = credential(&h, app.device)?;
    blocking(app, move |s| {
        let a = s.actor(&token)?;
        s.db.execute("DELETE FROM sessions WHERE id=?", [a.session_id])?;
        Ok(())
    })
    .await?;
    let mut r = Json(json!({"ok":true})).into_response();
    r.headers_mut().insert(
        "set-cookie",
        HeaderValue::from_static(
            "field_session=; HttpOnly; Secure; Path=/; SameSite=Strict; Max-Age=0",
        ),
    );
    Ok(r)
}
async fn reauth(
    State(app): State<App>,
    h: HeaderMap,
    Json(b): Json<Value>,
) -> Result<Json<Value>, Fault> {
    let token = credential(&h, app.device)?;
    let password = b["password"].as_str().unwrap_or("").to_owned();
    blocking(app, move |s| {
        let a = s.actor(&token)?;
        s.reauthenticate(&a, &password)
    })
    .await?;
    Ok(Json(json!({"ok":true})))
}
async fn snapshot(
    State(app): State<App>,
    h: HeaderMap,
) -> Result<Json<crate::store::Snapshot>, Fault> {
    let token = credential(&h, app.device)?;
    Ok(Json(
        blocking(app, move |s| {
            let actor = s.actor(&token)?;
            s.snapshot(actor)
        })
        .await?,
    ))
}
async fn head(State(app): State<App>, h: HeaderMap) -> Result<Json<Value>, Fault> {
    let token = credential(&h, app.device)?;
    Ok(Json(
        blocking(app, move |s| {
            s.actor(&token)?;
            let (e, g, q) = s.meta()?;
            Ok(json!({"epoch":e,"generation":g,"sequence":q}))
        })
        .await?,
    ))
}
async fn command(
    State(app): State<App>,
    h: HeaderMap,
    Json(c): Json<Command>,
) -> Result<Json<Value>, Fault> {
    let token = credential(&h, app.device)?;
    Ok(Json(blocking(app, move |s| s.command(&token, c)).await?))
}
async fn image(
    State(app): State<App>,
    h: HeaderMap,
    Path(oid): Path<String>,
) -> Result<Response, Fault> {
    let token = credential(&h, app.device)?;
    let bytes = blocking(app, move |s| {
        s.actor(&token)?;
        s.image(&oid)
    })
    .await?;
    Ok((
        [
            ("content-type", "image/png"),
            ("content-disposition", "inline; filename=attachment.png"),
        ],
        bytes,
    )
        .into_response())
}
async fn admin_get(State(app): State<App>, h: HeaderMap) -> Result<Json<Value>, Fault> {
    let token = credential(&h, app.device)?;
    Ok(Json(
        blocking(app, move |s| s.admin_snapshot(&s.actor(&token)?)).await?,
    ))
}
async fn admin_post(
    State(app): State<App>,
    h: HeaderMap,
    Json(b): Json<Value>,
) -> Result<Json<Value>, Fault> {
    let token = credential(&h, app.device)?;
    Ok(Json(blocking(app, move |s| s.admin(&token, b)).await?))
}
async fn backup(State(app): State<App>, h: HeaderMap) -> Result<Response, Fault> {
    let token = credential(&h, app.device)?;
    let data = blocking(app, move |s| {
        let a: Actor = s.actor(&token)?;
        if a.role != "owner" || !a.elevated {
            return Err(Fault(
                403,
                "Owner and recent reauthentication required".into(),
            ));
        }
        s.export_backup()
    })
    .await?;
    Ok((
        [
            ("content-type", "application/octet-stream"),
            (
                "content-disposition",
                "attachment; filename=field-notes.backup",
            ),
        ],
        data,
    )
        .into_response())
}
async fn invite(
    State(app): State<App>,
    h: HeaderMap,
    Json(input): Json<Value>,
) -> Result<Json<Value>, Fault> {
    let token = credential(&h, false)?;
    let user = input
        .get("user_id")
        .and_then(Value::as_str)
        .ok_or(Fault(400, "Choose a household user".into()))?
        .to_owned();
    if input.as_object().is_none_or(|v| v.len() != 1) {
        return Err(Fault(400, "Unexpected invitation fields".into()));
    }
    Ok(Json(
        blocking(app, move |s| s.enrollment_invite(&token, &user)).await?,
    ))
}
async fn devices(State(app): State<App>, h: HeaderMap) -> Result<Json<Value>, Fault> {
    let token = credential(&h, false)?;
    Ok(Json(
        blocking(app, move |s| s.enrollment_overview(&token)).await?,
    ))
}
async fn approve(
    State(app): State<App>,
    h: HeaderMap,
    Path(id): Path<String>,
    Json(input): Json<Value>,
) -> Result<Json<Value>, Fault> {
    let token = credential(&h, false)?;
    let code = input
        .get("verification")
        .and_then(Value::as_str)
        .filter(|c| c.len() <= 12)
        .ok_or(Fault(400, "Enter the phone verification code".into()))?
        .to_owned();
    if input.as_object().is_none_or(|v| v.len() != 1) {
        return Err(Fault(400, "Unexpected approval fields".into()));
    }
    Ok(Json(
        blocking(app, move |s| s.enrollment_approve(&token, &id, &code)).await?,
    ))
}
async fn revoke_device(
    State(app): State<App>,
    h: HeaderMap,
    Path(id): Path<String>,
) -> Result<Json<Value>, Fault> {
    let token = credential(&h, false)?;
    Ok(Json(
        blocking(app, move |s| s.enrollment_revoke(&token, &id)).await?,
    ))
}
async fn claim(
    State(app): State<App>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    Json(input): Json<Value>,
) -> Result<Json<Value>, Fault> {
    Ok(Json(
        blocking(app, move |s| {
            s.enrollment_claim(input, &peer.ip().to_string())
        })
        .await?,
    ))
}
async fn enrollment_status(
    State(app): State<App>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    Json(input): Json<Value>,
) -> Result<Json<Value>, Fault> {
    Ok(Json(
        blocking(app, move |s| {
            s.budget(&format!("enroll-status:{}", peer.ip()), 300)?;
            s.enrollment_status(input)
        })
        .await?,
    ))
}
async fn cancel_enrollment(
    State(app): State<App>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    Json(input): Json<Value>,
) -> Result<Json<Value>, Fault> {
    Ok(Json(
        blocking(app, move |s| {
            s.budget(&format!("enroll-cancel:{}", peer.ip()), 30)?;
            s.enrollment_cancel(input)
        })
        .await?,
    ))
}
async fn activate(
    State(app): State<App>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    Json(input): Json<Value>,
) -> Result<Json<Value>, Fault> {
    Ok(Json(
        blocking(app, move |s| {
            s.budget(&format!("activate:{}", peer.ip()), 60)?;
            s.activate_device(input, peer.ip())
        })
        .await?,
    ))
}
pub fn router(app: App) -> Router {
    let mut routes = Router::new()
        .route(
            "/health",
            get(|| async { Json(json!({"ok":true,"version":env!("CARGO_PKG_VERSION")})) }),
        )
        .route("/api/login", post(login))
        .route("/api/session", get(session))
        .route("/api/logout", post(logout))
        .route("/api/reauth", post(reauth))
        .route("/api/snapshot", get(snapshot))
        .route("/api/head", get(head))
        .route("/api/commands", post(command))
        .route("/api/images/{id}", get(image));
    if app.device {
        routes = routes.route("/api/device/activate", post(activate));
    }
    if !app.device {
        routes = routes
            .route("/api/enrollment/invitations", post(invite))
            .route("/api/enrollment/devices", get(devices))
            .route("/api/enrollment/devices/{id}/approve", post(approve))
            .route("/api/enrollment/devices/{id}/revoke", post(revoke_device))
            .route("/api/enrollment/claim", post(claim))
            .route("/api/enrollment/status", post(enrollment_status))
            .route("/api/enrollment/cancel", post(cancel_enrollment))
            .route("/api/admin", get(admin_get).post(admin_post))
            .route("/api/admin/backup", get(backup))
            .route(
                "/",
                get(|| async { Html(include_str!("../../admin/index.html")) }),
            )
            .route(
                "/organization.js",
                get(|| async {
                    (
                        [("content-type", "text/javascript; charset=utf-8")],
                        include_str!("../../admin/organization.js"),
                    )
                }),
            )
            .route(
                "/calendar.js",
                get(|| async {
                    (
                        [("content-type", "text/javascript; charset=utf-8")],
                        include_str!("../../admin/calendar.js"),
                    )
                }),
            )
            .route(
                "/management.js",
                get(|| async {
                    (
                        [("content-type", "text/javascript; charset=utf-8")],
                        include_str!("../../admin/management.js"),
                    )
                }),
            )
            .route(
                "/planning.js",
                get(|| async {
                    (
                        [("content-type", "text/javascript; charset=utf-8")],
                        include_str!("../../admin/planning.js"),
                    )
                }),
            )
            .route(
                "/app.js",
                get(|| async {
                    (
                        [("content-type", "text/javascript; charset=utf-8")],
                        include_str!("../../admin/app.js"),
                    )
                }),
            )
            .route(
                "/app.css",
                get(|| async {
                    (
                        [("content-type", "text/css; charset=utf-8")],
                        include_str!("../../admin/app.css"),
                    )
                }),
            );
    }
    routes
        .layer(DefaultBodyLimit::max(6_500_000))
        .layer(middleware::from_fn_with_state(app.clone(), secure))
        .with_state(app)
}
