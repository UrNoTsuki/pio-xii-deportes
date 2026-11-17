import base64
import csv
import io
import json
import os
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path
from typing import Optional

import httpx
import streamlit as st
from postgrest.exceptions import APIError
from streamlit.errors import StreamlitSecretNotFoundError
from supabase import Client, create_client
from supabase_auth.errors import AuthError


NOMBRES_COLUMNAS = ["id", "codigo", "disponible", "prestado_a", "fecha_prestamo"]
OPCIONES_NAVEGACION = [
    "Inicio",
    "Inventario",
    "Nuevo préstamo",
    "Devolución",
    "Administrar balones",
    "Calendario de profesores",
    "Tarde Deportiva",
]

st.set_page_config(
    page_title="Pio XII Deportes",
    page_icon="🏫",
    layout="wide",
    initial_sidebar_state="expanded",
)


def cargar_estilos() -> None:
    ruta_estilos = Path(__file__).with_name("styles.css")
    try:
        estilos = ruta_estilos.read_text(encoding="utf-8")
    except OSError as error:
        st.error(f"No se pudieron cargar los estilos de la página: {error}")
        st.stop()
    st.markdown(f"<style>{estilos}</style>", unsafe_allow_html=True)


def obtener_rol_clave(clave: str) -> Optional[str]:
    partes = clave.split(".")
    if len(partes) != 3:
        return None
    try:
        payload = base64.urlsafe_b64decode(
            partes[1] + "=" * (-len(partes[1]) % 4)
        )
        claims = json.loads(payload)
        rol = claims.get("role") if isinstance(claims, dict) else None
        return rol if isinstance(rol, str) else None
    except (ValueError, UnicodeDecodeError):
        return None


def es_clave_privilegiada(clave: str) -> bool:
    if clave.startswith("sb_secret_"):
        return True
    if clave.startswith("sb_publishable_"):
        return False
    rol = obtener_rol_clave(clave)
    return rol is not None and rol != "anon"


def es_clave_publica(clave: str) -> bool:
    return clave.startswith("sb_publishable_") or obtener_rol_clave(clave) == "anon"


def cargar_configuracion() -> tuple[str, str]:
    try:
        url_secreto = st.secrets.get("SUPABASE_URL")
        clave_publica_secreta = (
            st.secrets.get("SUPABASE_PUBLISHABLE_KEY")
            or st.secrets.get("SUPABASE_ANON_KEY")
        )
        clave_legacy_secreta = st.secrets.get("SUPABASE_KEY")
    except StreamlitSecretNotFoundError:
        url_secreto = None
        clave_publica_secreta = None
        clave_legacy_secreta = None

    url = os.getenv("SUPABASE_URL") or url_secreto
    clave = (
        os.getenv("SUPABASE_PUBLISHABLE_KEY")
        or os.getenv("SUPABASE_ANON_KEY")
        or clave_publica_secreta
    )
    clave_legacy = os.getenv("SUPABASE_KEY") or clave_legacy_secreta
    if not clave and clave_legacy and es_clave_publica(str(clave_legacy)):
        clave = clave_legacy

    if not url or not clave:
        if clave_legacy and es_clave_privilegiada(str(clave_legacy)):
            st.error(
                "SUPABASE_KEY contiene una clave secreta o service_role. "
                "Reemplázala por SUPABASE_PUBLISHABLE_KEY o SUPABASE_ANON_KEY."
            )
            st.stop()
        st.error(
            "Faltan las credenciales públicas de Supabase. Configura SUPABASE_URL "
            "y SUPABASE_PUBLISHABLE_KEY (o SUPABASE_ANON_KEY) en "
            ".streamlit/secrets.toml o como variables de entorno."
        )
        st.stop()

    if es_clave_privilegiada(str(clave)):
        st.error(
            "La aplicación no puede usar una clave secreta o service_role. "
            "Configura una clave publishable/anon; el acceso se controla con RLS."
        )
        st.stop()

    return str(url), str(clave)


def limpiar_sesion() -> None:
    st.session_state.pop("supabase_access_token", None)
    st.session_state.pop("supabase_refresh_token", None)
    st.session_state.pop("supabase_user_id", None)


def aplicar_token_usuario(cliente: Client, access_token: str) -> None:
    cliente.postgrest.auth(access_token)


def autenticar_sesion(cliente: Client) -> bool:
    access_token = st.session_state.get("supabase_access_token")
    refresh_token = st.session_state.get("supabase_refresh_token")
    if not access_token or not refresh_token:
        limpiar_sesion()
        return False

    try:
        respuesta = cliente.auth.set_session(access_token, refresh_token)
    except (AuthError, httpx.HTTPError):
        limpiar_sesion()
        st.session_state["auth_notice"] = (
            "La sesión venció o ya no es válida. Inicia sesión de nuevo."
        )
        return False

    if not respuesta.session:
        limpiar_sesion()
        st.session_state["auth_notice"] = (
            "La sesión venció o ya no es válida. Inicia sesión de nuevo."
        )
        return False

    st.session_state["supabase_access_token"] = respuesta.session.access_token
    st.session_state["supabase_refresh_token"] = respuesta.session.refresh_token
    st.session_state["supabase_user_id"] = str(respuesta.session.user.id)
    aplicar_token_usuario(cliente, respuesta.session.access_token)
    return True


def mostrar_login(cliente: Client) -> None:
    aviso = st.session_state.pop("auth_notice", None)
    aviso_cierre = st.session_state.pop("logout_notice", None)

    _, columna, _ = st.columns([1, 1.15, 1])
    with columna:
        st.markdown(
            '<div class="brand-mark login-brand"><span class="brand-icon">P</span>'
            '<div><strong>PIO XII</strong><small>DEPORTES</small></div></div>',
            unsafe_allow_html=True,
        )
        st.title("Iniciar sesión")
        st.markdown(
            '<p class="page-intro">Accede al espacio de control del material deportivo.</p>',
            unsafe_allow_html=True,
        )
        if aviso:
            st.warning(aviso)
        if aviso_cierre:
            st.warning(aviso_cierre)

        with st.form("formulario_login", clear_on_submit=True):
            correo = st.text_input(
                "Correo electrónico", placeholder="nombre@colegio.edu"
            )
            contrasena = st.text_input("Contraseña", type="password")
            enviar = st.form_submit_button("Entrar", use_container_width=True)

        if enviar:
            try:
                respuesta = cliente.auth.sign_in_with_password(
                    {"email": correo.strip(), "password": contrasena}
                )
            except AuthError:
                st.error("No se pudo iniciar sesión. Revisa el correo y la contraseña.")
            except httpx.HTTPError:
                st.error("No se pudo conectar con el servicio de autenticación.")
            else:
                if not respuesta.session:
                    st.error(
                        "No se pudo iniciar sesión. Revisa el correo y la contraseña."
                    )
                else:
                    st.session_state["supabase_access_token"] = (
                        respuesta.session.access_token
                    )
                    st.session_state["supabase_refresh_token"] = (
                        respuesta.session.refresh_token
                    )
                    st.session_state["supabase_user_id"] = str(
                        respuesta.session.user.id
                    )
                    st.rerun()


def reportar_error(accion: str, error: Exception) -> None:
    if isinstance(error, APIError):
        if "row-level security policy" in str(error).casefold():
            access_token = st.session_state.get("supabase_access_token", "")
            rol = obtener_rol_clave(access_token) if access_token else None
            st.error(
                f"Supabase no pudo {accion}: {error}\n\n"
                f"Diagnóstico: el rol enviado es `{rol or 'desconocido'}`. "
                "Se necesita `authenticated`."
            )
        else:
            st.error(f"Supabase no pudo {accion}: {error}")
    elif isinstance(error, httpx.HTTPError):
        st.error(f"No se pudo conectar con Supabase al {accion}: {error}")
    else:
        raise error


def obtener_inventario(cliente: Client) -> list[dict]:
    try:
        respuesta = (
            cliente.table("balones")
            .select("*")
            .order("codigo")
            .execute()
        )
    except (APIError, httpx.HTTPError) as error:
        reportar_error("consultar el inventario", error)
        st.stop()
    return respuesta.data or []


def obtener_eventos_calendario(cliente: Client) -> list[dict]:
    try:
        respuesta = (
            cliente.table("teacher_calendar_events")
            .select("id, owner_id, teacher_name, event_title, event_date, start_time, end_time")
            .order("event_date")
            .order("start_time")
            .execute()
        )
    except (APIError, httpx.HTTPError) as error:
        reportar_error("consultar el calendario de profesores", error)
        st.stop()
    return respuesta.data or []


def crear_evento_calendario(
    cliente: Client,
    teacher_name: str,
    event_title: str,
    event_date: date,
    start_time: time,
    end_time: time,
) -> bool:
    if end_time <= start_time:
        st.error("La hora de finalización debe ser posterior a la hora de inicio.")
        return False
    try:
        cliente.table("teacher_calendar_events").insert(
            {
                "teacher_name": teacher_name.strip(),
                "event_title": event_title.strip(),
                "event_date": event_date.isoformat(),
                "start_time": start_time.isoformat(),
                "end_time": end_time.isoformat(),
            }
        ).execute()
    except (APIError, httpx.HTTPError) as error:
        reportar_error("guardar el evento", error)
        return False
    st.success("Evento agregado al calendario.")
    return True


def cancelar_evento_calendario(
    cliente: Client, event_id: str, owner_id: str
) -> bool:
    try:
        respuesta = (
            cliente.table("teacher_calendar_events")
            .delete()
            .eq("id", event_id)
            .eq("owner_id", owner_id)
            .select("id")
            .execute()
        )
    except (APIError, httpx.HTTPError) as error:
        reportar_error("cancelar el evento", error)
        return False
    if not respuesta.data:
        st.error("No se canceló el evento. Solo puedes cancelar tus propios eventos.")
        return False
    st.success("Evento cancelado.")
    return True


def obtener_partidos_tarde_deportiva(cliente: Client) -> list[dict]:
    try:
        respuesta = (
            cliente.table("tarde_deportiva_matches")
            .select(
                "id, match_date, sport, start_time, team_a, team_b, score_a, score_b, notes"
            )
            .order("match_date", desc=True)
            .order("start_time")
            .execute()
        )
    except (APIError, httpx.HTTPError) as error:
        reportar_error("consultar Tarde Deportiva", error)
        st.stop()
    return respuesta.data or []


def crear_partido_tarde_deportiva(
    cliente: Client,
    match_date: date,
    sport: str,
    start_time: time,
    team_a: str,
    team_b: str,
    notes: str,
) -> bool:
    if match_date.weekday() != 4:
        st.error("Tarde Deportiva solo admite partidos de los viernes.")
        return False
    if not sport.strip() or not team_a.strip() or not team_b.strip():
        st.error("Completa el deporte y los nombres de los dos equipos.")
        return False
    try:
        cliente.table("tarde_deportiva_matches").insert(
            {
                "match_date": match_date.isoformat(),
                "sport": sport.strip(),
                "start_time": start_time.isoformat(),
                "team_a": team_a.strip(),
                "team_b": team_b.strip(),
                "notes": notes.strip() or None,
            }
        ).execute()
    except (APIError, httpx.HTTPError) as error:
        reportar_error("guardar el partido", error)
        return False
    st.success("Partido agregado a Tarde Deportiva.")
    return True


def guardar_resultado_tarde_deportiva(
    cliente: Client, match_id: str, score_a: int, score_b: int
) -> bool:
    try:
        respuesta = (
            cliente.table("tarde_deportiva_matches")
            .update({"score_a": score_a, "score_b": score_b})
            .eq("id", match_id)
            .select("id")
            .execute()
        )
    except (APIError, httpx.HTTPError) as error:
        reportar_error("guardar el resultado", error)
        return False
    if not respuesta.data:
        st.error("No se guardó el resultado.")
        return False
    st.success("Resultado guardado.")
    return True


def eliminar_partido_tarde_deportiva(cliente: Client, match_id: str) -> bool:
    try:
        respuesta = (
            cliente.table("tarde_deportiva_matches")
            .delete()
            .eq("id", match_id)
            .select("id")
            .execute()
        )
    except (APIError, httpx.HTTPError) as error:
        reportar_error("eliminar el partido", error)
        return False
    if not respuesta.data:
        st.error("No se eliminó el partido.")
        return False
    st.success("Partido eliminado.")
    return True


def fecha_legible(fecha: object) -> str:
    if not fecha:
        return "Fecha no disponible"
    try:
        instante = datetime.fromisoformat(str(fecha).replace("Z", "+00:00"))
    except ValueError:
        return "Fecha no válida"
    if instante.tzinfo is None:
        instante = instante.replace(tzinfo=timezone.utc)
    dias = max((datetime.now(timezone.utc) - instante.astimezone(timezone.utc)).days, 0)
    if dias == 0:
        return "Hoy"
    if dias == 1:
        return "Hace 1 día"
    return f"Hace {dias} días"


def actualizar_prestamo(
    cliente: Client, codigo_balon: str, nombre_estudiante: str
) -> bool:
    try:
        respuesta = (
            cliente.table("balones")
            .update(
                {
                    "disponible": False,
                    "prestado_a": nombre_estudiante,
                    "fecha_prestamo": datetime.now(timezone.utc).isoformat(),
                }
            )
            .eq("codigo", codigo_balon)
            .eq("disponible", True)
            .select("codigo")
            .execute()
        )
        if respuesta.data:
            st.success(f"Préstamo registrado: {codigo_balon} para {nombre_estudiante}.")
            return True

        consulta = (
            cliente.table("balones")
            .select("disponible")
            .eq("codigo", codigo_balon)
            .execute()
        )
        if not consulta.data:
            st.error(f"No existe un balón con el código {codigo_balon}.")
        else:
            st.error(f"El balón {codigo_balon} ya está prestado.")
    except (APIError, httpx.HTTPError) as error:
        reportar_error("registrar el préstamo", error)
    return False


def actualizar_devolucion(cliente: Client, codigo_balon: str) -> bool:
    try:
        respuesta = (
            cliente.table("balones")
            .update(
                {
                    "disponible": True,
                    "prestado_a": None,
                    "fecha_prestamo": None,
                }
            )
            .eq("codigo", codigo_balon)
            .eq("disponible", False)
            .select("codigo")
            .execute()
        )
        if respuesta.data:
            st.success(f"Devolución registrada: {codigo_balon} está disponible.")
            return True

        consulta = (
            cliente.table("balones")
            .select("disponible")
            .eq("codigo", codigo_balon)
            .execute()
        )
        if not consulta.data:
            st.error(f"No existe un balón con el código {codigo_balon}.")
        else:
            st.error(f"El balón {codigo_balon} ya estaba disponible.")
    except (APIError, httpx.HTTPError) as error:
        reportar_error("registrar la devolución", error)
    return False


def agregar_balones(cliente: Client, codigos: list[str]) -> bool:
    try:
        respuesta = (
            cliente.table("balones")
            .insert([{"codigo": codigo, "disponible": True} for codigo in codigos])
            .select("codigo")
            .execute()
        )
    except (APIError, httpx.HTTPError) as error:
        reportar_error("agregar los balones", error)
        return False
    if len(respuesta.data or []) != len(codigos):
        st.error("No se pudieron confirmar todos los balones agregados.")
        return False
    st.success(f"Se agregaron {len(codigos)} balón(es) al inventario.")
    return True


def cambiar_codigo(cliente: Client, codigo_actual: str, codigo_nuevo: str) -> bool:
    try:
        respuesta = (
            cliente.table("balones")
            .update({"codigo": codigo_nuevo})
            .eq("codigo", codigo_actual)
            .eq("disponible", True)
            .select("codigo")
            .execute()
        )
    except (APIError, httpx.HTTPError) as error:
        reportar_error("cambiar el código del balón", error)
        return False
    if not respuesta.data:
        st.error("No se pudo cambiar el código. Confirma que el balón siga disponible.")
        return False
    st.success(f"Código actualizado: {codigo_actual} → {codigo_nuevo}.")
    return True


def eliminar_balon(cliente: Client, codigo_balon: str) -> bool:
    try:
        respuesta = (
            cliente.table("balones")
            .delete()
            .eq("codigo", codigo_balon)
            .eq("disponible", True)
            .select("codigo")
            .execute()
        )
    except (APIError, httpx.HTTPError) as error:
        reportar_error("eliminar el balón", error)
        return False
    if not respuesta.data:
        st.error("No se eliminó: el balón puede estar prestado o ya no existir.")
        return False
    st.success(f"El balón {codigo_balon} se eliminó del inventario.")
    return True


def preparar_csv(balones: list[dict]) -> bytes:
    salida = io.StringIO(newline="")
    escritor = csv.DictWriter(salida, fieldnames=NOMBRES_COLUMNAS, extrasaction="ignore")
    escritor.writeheader()
    escritor.writerows(balones)
    return salida.getvalue().encode("utf-8-sig")


def mostrar_formulario_prestamo(
    cliente: Client, disponibles: list[dict], llave: str
) -> None:
    if not disponibles:
        st.info("No hay balones disponibles. Puedes revisar los préstamos pendientes en Devolución.")
        return

    codigos = [str(balon["codigo"]) for balon in disponibles if balon.get("codigo")]
    if not codigos:
        st.warning("Hay registros disponibles, pero ninguno tiene un código válido.")
        return

    with st.form(f"formulario_prestamo_{llave}"):
        nombre = st.text_input("Nombre del estudiante", placeholder="Ej.: Alex Rivera")
        codigo = st.selectbox("Balón disponible", codigos)
        st.caption(f"{len(codigos)} balón(es) listo(s) para préstamo.")
        enviar = st.form_submit_button("Confirmar préstamo", use_container_width=True)
    if enviar:
        nombre = nombre.strip()
        if not nombre:
            st.error("Escribe el nombre del estudiante para continuar.")
        elif actualizar_prestamo(cliente, codigo, nombre):
            st.rerun()


def mostrar_formulario_devolucion(
    cliente: Client, prestados: list[dict], llave: str
) -> None:
    if not prestados:
        st.info("No hay balones pendientes de devolución.")
        return

    con_codigo = [balon for balon in prestados if balon.get("codigo")]
    if not con_codigo:
        st.warning("Hay préstamos registrados sin código de balón.")
        return

    por_codigo = {str(balon["codigo"]): balon for balon in con_codigo}
    codigos = list(por_codigo)
    with st.form(f"formulario_devolucion_{llave}"):
        codigo = st.selectbox("Balón que se devuelve", codigos)
        balon = por_codigo[codigo]
        st.caption(
            f"Lo tiene {balon.get('prestado_a') or 'estudiante sin nombre'} · "
            f"prestado {fecha_legible(balon.get('fecha_prestamo')).lower()}."
        )
        confirmar = st.form_submit_button(
            "Confirmar devolución", use_container_width=True
        )
    if confirmar and actualizar_devolucion(cliente, codigo):
        st.rerun()


def mostrar_inicio(cliente: Client, inventario: list[dict]) -> None:
    disponibles = [balon for balon in inventario if balon.get("disponible") is True]
    prestados = [balon for balon in inventario if balon.get("disponible") is False]

    st.title("Control de material deportivo")
    st.markdown(
        '<p class="page-intro">Consulta qué balones están disponibles y registra '
        "préstamos y devoluciones.</p>",
        unsafe_allow_html=True,
    )
    metricas = st.columns(3)
    metricas[0].metric("Balones en total", len(inventario))
    metricas[1].metric("Disponibles ahora", len(disponibles))
    metricas[2].metric("En préstamo", len(prestados))

    st.markdown(
        '<div class="section-heading"><div><span class="section-eyebrow">MOVIMIENTOS</span>'
        '<h2>Registrar préstamo o devolución</h2></div></div>',
        unsafe_allow_html=True,
    )
    prestamo_col, devolucion_col = st.columns(2)
    with prestamo_col:
        st.markdown("#### Nuevo préstamo")
        mostrar_formulario_prestamo(cliente, disponibles, "inicio")
    with devolucion_col:
        st.markdown("#### Devolución")
        mostrar_formulario_devolucion(cliente, prestados, "inicio")

    if prestados:
        st.markdown(
            '<div class="section-heading compact-heading"><div>'
            '<span class="section-eyebrow">PENDIENTES</span>'
            '<h2>Balones prestados</h2></div></div>',
            unsafe_allow_html=True,
        )
        filas = []
        for balon in prestados:
            filas.append(
                {
                    "Código": balon.get("codigo", ""),
                    "Prestado a": balon.get("prestado_a") or "Sin nombre",
                    "Desde": fecha_legible(balon.get("fecha_prestamo")),
                }
            )
        st.dataframe(filas, use_container_width=True, hide_index=True)


def mostrar_inventario(inventario: list[dict]) -> None:
    st.title("Inventario")
    st.markdown(
        '<p class="page-intro">Busca un balón o estudiante, filtra la lista y descarga '
        "los resultados.</p>",
        unsafe_allow_html=True,
    )
    busqueda_col, estado_col = st.columns([2, 1])
    with busqueda_col:
        busqueda = st.text_input(
            "Buscar",
            placeholder="Código del balón o nombre del estudiante",
        ).strip().casefold()
    with estado_col:
        estado = st.selectbox(
            "Estado",
            ["Todos", "Disponibles", "Prestados"],
        )

    filtrados = []
    for balon in inventario:
        esta_disponible = balon.get("disponible") is True
        if estado == "Disponibles" and not esta_disponible:
            continue
        if estado == "Prestados" and esta_disponible:
            continue
        texto = f"{balon.get('codigo', '')} {balon.get('prestado_a') or ''}".casefold()
        if busqueda and busqueda not in texto:
            continue
        filtrados.append(balon)

    st.caption(f"Mostrando {len(filtrados)} de {len(inventario)} balón(es).")
    exportar_col, _ = st.columns([1, 3])
    with exportar_col:
        st.download_button(
            "Descargar CSV",
            data=preparar_csv(filtrados),
            file_name="inventario_balones.csv",
            mime="text/csv",
            use_container_width=True,
            disabled=not filtrados,
        )

    if filtrados:
        st.dataframe(filtrados, use_container_width=True)
    elif inventario:
        st.info("No hay resultados con esa búsqueda y filtro.")
    else:
        st.info("Todavía no hay balones registrados. Agrégalos desde Administrar balones.")


def mostrar_admin_balones(cliente: Client, inventario: list[dict]) -> None:
    disponibles = [balon for balon in inventario if balon.get("disponible") is True]
    codigos_existentes = {
        str(balon.get("codigo", "")).strip().casefold()
        for balon in inventario
        if balon.get("codigo")
    }

    st.title("Administrar balones")
    st.markdown(
        '<p class="page-intro">Agrega balones, corrige sus códigos o retira material '
        "disponible. Los balones prestados no se pueden editar ni retirar.</p>",
        unsafe_allow_html=True,
    )

    agregar_tab, editar_tab, eliminar_tab = st.tabs(
        ["Agregar", "Cambiar código", "Retirar"]
    )
    with agregar_tab:
        st.markdown("#### Agregar uno o varios balones")
        st.caption("Escribe un código por línea. Se omiten líneas vacías y duplicados.")
        with st.form("formulario_agregar"):
            texto_codigos = st.text_area(
                "Códigos nuevos",
                placeholder="FUT-01\nBAS-01\nVOL-01",
                height=120,
            )
            confirmar_agregar = st.form_submit_button(
                "Agregar al inventario", use_container_width=True
            )
        if confirmar_agregar:
            codigos = []
            duplicados = []
            vistos = set(codigos_existentes)
            for linea in texto_codigos.splitlines():
                codigo = linea.strip()
                if not codigo:
                    continue
                normalizado = codigo.casefold()
                if normalizado in vistos:
                    duplicados.append(codigo)
                    continue
                vistos.add(normalizado)
                codigos.append(codigo)

            if duplicados:
                st.error(
                    "Estos códigos ya existen o están repetidos en la lista: "
                    + ", ".join(duplicados)
                    + ". No se agregó ningún balón."
                )
            elif not codigos:
                st.error("Escribe al menos un código de balón.")
            elif agregar_balones(cliente, codigos):
                st.rerun()

    with editar_tab:
        st.markdown("#### Corregir el código de un balón disponible")
        if not disponibles:
            st.info("No hay balones disponibles para editar.")
        else:
            codigos_disponibles = [
                str(balon["codigo"])
                for balon in disponibles
                if balon.get("codigo")
            ]
            with st.form("formulario_editar_codigo"):
                codigo_actual = st.selectbox(
                    "Balón que quieres corregir", codigos_disponibles
                )
                codigo_nuevo = st.text_input(
                    "Código nuevo", placeholder="Ej.: FUT-02"
                )
                confirmar_edicion = st.form_submit_button(
                    "Guardar código", use_container_width=True
                )
            if confirmar_edicion:
                codigo_nuevo = codigo_nuevo.strip()
                normalizado = codigo_nuevo.casefold()
                if not codigo_nuevo:
                    st.error("Escribe el código nuevo.")
                elif codigo_nuevo == codigo_actual:
                    st.error("El código nuevo es igual al actual.")
                elif normalizado in codigos_existentes:
                    st.error("Ese código ya está registrado.")
                elif cambiar_codigo(cliente, codigo_actual, codigo_nuevo):
                    st.rerun()

    with eliminar_tab:
        st.markdown("#### Retirar un balón del inventario")
        if not disponibles:
            st.info("No hay balones disponibles para retirar.")
        else:
            codigos_disponibles = [
                str(balon["codigo"])
                for balon in disponibles
                if balon.get("codigo")
            ]
            with st.form("formulario_eliminar_balon"):
                codigo_eliminar = st.selectbox(
                    "Balón que quieres retirar", codigos_disponibles
                )
                confirmar_eliminar = st.checkbox(
                    f"Confirmo que quiero eliminar {codigo_eliminar} permanentemente."
                )
                enviar_eliminar = st.form_submit_button(
                    "Eliminar balón", use_container_width=True
                )
            if enviar_eliminar:
                if not confirmar_eliminar:
                    st.error("Marca la confirmación antes de eliminar el balón.")
                elif eliminar_balon(cliente, codigo_eliminar):
                    st.rerun()


def mostrar_calendario_profesores(
    cliente: Client, eventos: list[dict], usuario_id: str
) -> None:
    st.title("Calendario de profesores")
    st.markdown(
        '<p class="page-intro">Agenda clases, exámenes y reuniones. Todos los '
        "profesores pueden consultar el calendario; cada persona puede cancelar "
        "sus propios eventos.</p>",
        unsafe_allow_html=True,
    )

    with st.expander("Agregar un evento", expanded=not eventos):
        with st.form("formulario_evento_calendario"):
            nombre_col, evento_col = st.columns(2)
            with nombre_col:
                teacher_name = st.text_input("Nombre del profesor")
            with evento_col:
                event_title = st.text_input(
                    "Evento", placeholder="Clase, examen, reunión..."
                )
            fecha_col, inicio_col, fin_col = st.columns(3)
            with fecha_col:
                event_date = st.date_input("Fecha", value=date.today())
            with inicio_col:
                start_time = st.time_input("Hora de inicio", value=time(8, 0))
            with fin_col:
                end_time = st.time_input("Hora de finalización", value=time(8, 45))
            guardar_evento = st.form_submit_button(
                "Agregar al calendario", use_container_width=True
            )
        if guardar_evento:
            if not teacher_name.strip() or not event_title.strip():
                st.error("Escribe el nombre del profesor y el evento.")
            elif crear_evento_calendario(
                cliente,
                teacher_name,
                event_title,
                event_date,
                start_time,
                end_time,
            ):
                st.rerun()

    st.markdown("### Consultar eventos")
    fecha_consulta = st.date_input(
        "Ver eventos del día",
        value=date.today(),
        key="fecha_consulta_calendario",
    )
    eventos_dia = [
        evento
        for evento in eventos
        if str(evento.get("event_date", "")) == fecha_consulta.isoformat()
    ]
    if eventos_dia:
        filas = [
            {
                "Hora": (
                    f"{str(evento.get('start_time', ''))[:5]}–"
                    f"{str(evento.get('end_time', ''))[:5]}"
                ),
                "Profesor": evento.get("teacher_name", ""),
                "Evento": evento.get("event_title", ""),
            }
            for evento in eventos_dia
        ]
        st.dataframe(filas, use_container_width=True, hide_index=True)
    else:
        st.info("No hay eventos agendados para este día.")

    propios = [evento for evento in eventos if evento.get("owner_id") == usuario_id]
    if propios:
        st.markdown("### Cancelar uno de mis eventos")
        evento_por_etiqueta = {
            (
                f"{evento.get('event_date')} · "
                f"{str(evento.get('start_time', ''))[:5]} · "
                f"{evento.get('event_title')} ({evento.get('teacher_name')}) · "
                f"{str(evento.get('id', ''))[:8]}"
            ): evento
            for evento in propios
        }
        with st.form("formulario_cancelar_evento"):
            etiqueta = st.selectbox(
                "Evento propio", list(evento_por_etiqueta)
            )
            confirmar = st.checkbox("Confirmo que quiero cancelar este evento.")
            cancelar = st.form_submit_button(
                "Cancelar evento", use_container_width=True
            )
        if cancelar:
            if not confirmar:
                st.error("Confirma la cancelación para continuar.")
            elif cancelar_evento_calendario(
                cliente, str(evento_por_etiqueta[etiqueta]["id"]), usuario_id
            ):
                st.rerun()

    if eventos:
        with st.expander("Ver todos los eventos"):
            filas = [
                {
                    "Fecha": evento.get("event_date", ""),
                    "Inicio": str(evento.get("start_time", ""))[:5],
                    "Fin": str(evento.get("end_time", ""))[:5],
                    "Profesor": evento.get("teacher_name", ""),
                    "Evento": evento.get("event_title", ""),
                }
                for evento in eventos
            ]
            st.dataframe(filas, use_container_width=True, hide_index=True)


def viernes_siguiente(fecha: date) -> date:
    dias_hasta_viernes = (4 - fecha.weekday()) % 7
    return fecha + timedelta(days=dias_hasta_viernes)


def mostrar_tarde_deportiva(
    cliente: Client, partidos: list[dict]
) -> None:
    st.title("Tarde Deportiva")
    st.markdown(
        '<p class="page-intro">Programa los enfrentamientos del viernes antes de los '
        "partidos, registra los resultados después y consulta el historial.</p>",
        unsafe_allow_html=True,
    )

    fecha_partidos = st.date_input(
        "Viernes que quieres consultar",
        value=viernes_siguiente(date.today()),
        key="fecha_tarde_deportiva",
    )
    if fecha_partidos.weekday() != 4:
        st.warning("Selecciona un viernes para consultar o agregar partidos.")
        return

    partidos_semana = [
        partido
        for partido in partidos
        if str(partido.get("match_date", "")) == fecha_partidos.isoformat()
    ]
    partidos_jugados = [
        partido
        for partido in partidos_semana
        if partido.get("score_a") is not None
        and partido.get("score_b") is not None
    ]
    empates = sum(
        partido["score_a"] == partido["score_b"] for partido in partidos_jugados
    )
    deportes = {str(partido["sport"]) for partido in partidos_semana}
    equipos: dict[str, dict[str, int]] = {}
    for partido in partidos_jugados:
        equipo_a = str(partido["team_a"])
        equipo_b = str(partido["team_b"])
        marcador_a = int(partido["score_a"])
        marcador_b = int(partido["score_b"])
        for equipo in (equipo_a, equipo_b):
            equipos.setdefault(
                equipo,
                {"PJ": 0, "V": 0, "E": 0, "D": 0, "PF": 0, "PC": 0},
            )
        equipos[equipo_a]["PJ"] += 1
        equipos[equipo_b]["PJ"] += 1
        equipos[equipo_a]["PF"] += marcador_a
        equipos[equipo_a]["PC"] += marcador_b
        equipos[equipo_b]["PF"] += marcador_b
        equipos[equipo_b]["PC"] += marcador_a
        if marcador_a > marcador_b:
            equipos[equipo_a]["V"] += 1
            equipos[equipo_b]["D"] += 1
        elif marcador_b > marcador_a:
            equipos[equipo_b]["V"] += 1
            equipos[equipo_a]["D"] += 1
        else:
            equipos[equipo_a]["E"] += 1
            equipos[equipo_b]["E"] += 1

    metricas = st.columns(5)
    metricas[0].metric("Partidos", len(partidos_semana))
    metricas[1].metric("Deportes", len(deportes))
    metricas[2].metric(
        "Pendientes", len(partidos_semana) - len(partidos_jugados)
    )
    metricas[3].metric("Partidos con ganador", len(partidos_jugados) - empates)
    metricas[4].metric("Empates", empates)

    if partidos_semana:
        filas = [
            {
                "Hora": str(partido.get("start_time", ""))[:5],
                "Deporte": partido.get("sport", ""),
                "Equipo A": partido.get("team_a", ""),
                "Resultado": (
                    f"{partido['score_a']} – {partido['score_b']}"
                    if partido.get("score_a") is not None
                    and partido.get("score_b") is not None
                    else "Pendiente"
                ),
                "Equipo B": partido.get("team_b", ""),
                "Notas": partido.get("notes") or "",
            }
            for partido in partidos_semana
        ]
        st.dataframe(filas, use_container_width=True, hide_index=True)
        st.markdown("#### Resumen por equipo")
        tabla_equipos = [
            {"Equipo": nombre, **resumen}
            for nombre, resumen in sorted(equipos.items())
        ]
        st.dataframe(tabla_equipos, use_container_width=True, hide_index=True)
    else:
        st.info("Todavía no hay enfrentamientos registrados para este viernes.")

    with st.expander("Agregar un enfrentamiento", expanded=not partidos_semana):
        with st.form("formulario_enfrentamiento_tarde_deportiva"):
            deporte_col, hora_col = st.columns(2)
            with deporte_col:
                deporte = st.text_input(
                    "Deporte", placeholder="Fútbol, baloncesto, voleibol..."
                )
            with hora_col:
                hora_partido = st.time_input(
                    "Hora del partido", value=time(13, 0)
                )
            equipo_col, equipo_b_col = st.columns(2)
            with equipo_col:
                equipo_a = st.text_input("Equipo A")
            with equipo_b_col:
                equipo_b = st.text_input("Equipo B")
            notas = st.text_input("Notas (opcional)")
            guardar_enfrentamiento = st.form_submit_button(
                "Guardar enfrentamiento", use_container_width=True
            )
        if guardar_enfrentamiento:
            if crear_partido_tarde_deportiva(
                cliente,
                fecha_partidos,
                deporte,
                hora_partido,
                equipo_a,
                equipo_b,
                notas,
            ):
                st.rerun()

    if partidos_semana:
        partidos_por_etiqueta = {
            (
                f"{partido.get('start_time', '')[:5]} · "
                f"{partido.get('sport')} · {partido.get('team_a')} vs "
                f"{partido.get('team_b')} · {str(partido.get('id', ''))[:8]}"
            ): partido
            for partido in partidos_semana
        }
        with st.expander("Agregar o actualizar resultados"):
            etiqueta_resultado = st.selectbox(
                "Enfrentamiento", list(partidos_por_etiqueta), key="enfrentamiento_resultado"
            )
            partido_resultado = partidos_por_etiqueta[etiqueta_resultado]
            id_resultado = str(partido_resultado["id"])
            marcador_col_a, marcador_col_b = st.columns(2)
            with marcador_col_a:
                marcador_a = st.number_input(
                    f"Puntos {partido_resultado['team_a']}",
                    min_value=0,
                    step=1,
                    value=int(partido_resultado["score_a"] or 0),
                    key=f"resultado_{id_resultado}_a",
                )
            with marcador_col_b:
                marcador_b = st.number_input(
                    f"Puntos {partido_resultado['team_b']}",
                    min_value=0,
                    step=1,
                    value=int(partido_resultado["score_b"] or 0),
                    key=f"resultado_{id_resultado}_b",
                )
            if st.button(
                "Guardar resultado",
                key=f"guardar_resultado_{id_resultado}",
                use_container_width=True,
            ):
                if guardar_resultado_tarde_deportiva(
                    cliente, id_resultado, int(marcador_a), int(marcador_b)
                ):
                    st.rerun()

    if partidos:
        st.markdown("### Historial de Tardes Deportivas")
        fechas_historial = sorted(
            {str(partido["match_date"]) for partido in partidos},
            reverse=True,
        )
        historial = []
        for fecha in fechas_historial:
            juegos = [
                partido for partido in partidos
                if str(partido.get("match_date")) == fecha
            ]
            historial.append(
                {
                    "Viernes": fecha,
                    "Partidos": len(juegos),
                    "Deportes": ", ".join(
                        sorted({str(partido["sport"]) for partido in juegos})
                    ),
                }
            )
        st.dataframe(historial, use_container_width=True, hide_index=True)

        if partidos_semana:
            with st.form("formulario_eliminar_partido"):
                etiqueta = st.selectbox(
                    "Enfrentamiento que quieres eliminar",
                    list(partidos_por_etiqueta),
                )
                confirmar = st.checkbox(
                    "Confirmo que quiero eliminar este enfrentamiento del historial."
                )
                eliminar = st.form_submit_button(
                    "Eliminar enfrentamiento", use_container_width=True
                )
            if eliminar:
                if not confirmar:
                    st.error("Confirma la eliminación para continuar.")
                elif eliminar_partido_tarde_deportiva(
                    cliente, str(partidos_por_etiqueta[etiqueta]["id"])
                ):
                    st.rerun()


cargar_estilos()
SUPABASE_URL, SUPABASE_PUBLIC_KEY = cargar_configuracion()
supabase: Client = create_client(SUPABASE_URL, SUPABASE_PUBLIC_KEY)
if not autenticar_sesion(supabase):
    mostrar_login(supabase)
    st.stop()

inventario = obtener_inventario(supabase)
disponibles = [balon for balon in inventario if balon.get("disponible") is True]
prestados = [balon for balon in inventario if balon.get("disponible") is False]
eventos_calendario = obtener_eventos_calendario(supabase)
partidos_tarde_deportiva = obtener_partidos_tarde_deportiva(supabase)
usuario_id = str(st.session_state["supabase_user_id"])

with st.sidebar:
    st.markdown(
        '<div class="brand-mark"><span class="brand-icon">P</span>'
        '<div><strong>PIO XII</strong><small>DEPORTES</small></div></div>',
        unsafe_allow_html=True,
    )
    st.markdown(
        '<div class="sidebar-label">ESPACIO DE TRABAJO</div>',
        unsafe_allow_html=True,
    )
    opcion = st.radio(
        "Navegación",
        OPCIONES_NAVEGACION,
        label_visibility="collapsed",
        key="navegacion",
    )
    st.markdown(
        f'<div class="sidebar-note"><span class="live-dot"></span>'
        f"{len(disponibles)} disponibles · {len(prestados)} prestados"
        "<br><small>Estado al cargar esta página</small></div>",
        unsafe_allow_html=True,
    )
    if st.button("Cerrar sesión", use_container_width=True):
        try:
            supabase.auth.sign_out()
        except (AuthError, httpx.HTTPError):
            st.session_state["logout_notice"] = (
                "Se cerró la sesión en este navegador, pero no se pudo revocar "
                "la sesión en el servidor."
            )
        limpiar_sesion()
        st.rerun()

st.markdown(
    '<div class="page-kicker">PIO XII &nbsp;·&nbsp; DEPORTES</div>',
    unsafe_allow_html=True,
)

if opcion == "Inicio":
    mostrar_inicio(supabase, inventario)
elif opcion == "Inventario":
    mostrar_inventario(inventario)
elif opcion == "Nuevo préstamo":
    st.title("Registrar préstamo")
    st.markdown(
        '<p class="page-intro">Selecciona un balón disponible e indica quién lo recibe.</p>',
        unsafe_allow_html=True,
    )
    mostrar_formulario_prestamo(supabase, disponibles, "pagina")
elif opcion == "Devolución":
    st.title("Registrar devolución")
    st.markdown(
        '<p class="page-intro">Selecciona el balón que se devuelve para marcarlo '
        "como disponible.</p>",
        unsafe_allow_html=True,
    )
    mostrar_formulario_devolucion(supabase, prestados, "pagina")
elif opcion == "Administrar balones":
    mostrar_admin_balones(supabase, inventario)
elif opcion == "Calendario de profesores":
    mostrar_calendario_profesores(supabase, eventos_calendario, usuario_id)
else:
    mostrar_tarde_deportiva(supabase, partidos_tarde_deportiva)

st.markdown(
    '<div class="page-footer"><span>PIO XII DEPORTES</span></div>',
    unsafe_allow_html=True,
)
