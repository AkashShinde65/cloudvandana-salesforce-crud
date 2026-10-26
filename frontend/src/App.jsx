import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  AlertCircle,
  BriefcaseBusiness,
  Check,
  ChevronDown,
  CircleHelp,
  Contact,
  Database,
  Eye,
  FileText,
  LayoutDashboard,
  LoaderCircle,
  LogOut,
  Menu,
  Pencil,
  Plus,
  RefreshCw,
  Search,
  ShieldCheck,
  Trash2,
  UserRound,
  X,
} from 'lucide-react'
import './App.css'

const API_BASE = import.meta.env.DEV
  ? 'http://localhost:8080'
  : ''
const ALLOWED_OBJECTS = ['Account', 'Opportunity', 'Lead', 'Contact', 'Case']
const DISPLAY_FIELDS = {
  Account: ['Name', 'Phone', 'Industry', 'Website', 'Type'],
  Opportunity: ['Name', 'StageName', 'Amount', 'CloseDate', 'Probability'],
  Lead: ['FirstName', 'LastName', 'Company', 'Email', 'Status', 'Phone'],
  Contact: ['FirstName', 'LastName', 'Email', 'Phone', 'Title'],
  Case: ['CaseNumber', 'Subject', 'Status', 'Priority', 'Origin'],
}

const icons = {
  Account: BriefcaseBusiness,
  Opportunity: Database,
  Lead: UserRound,
  Contact,
  Case: FileText,
}

async function request(path, options = {}) {
  const response = await fetch(`${API_BASE}${path}`, {
    credentials: 'include',
    ...options,
    headers: { Accept: 'application/json', ...(options.headers || {}) },
  })
  const text = await response.text()
  let data
  try {
    data = text ? JSON.parse(text) : null
  } catch {
    data = text
  }
  if (!response.ok) {
    const error = new Error(
      data?.message || data?.error || (response.status === 401 ? 'Your session has expired. Please reconnect.' : `Request failed (${response.status}).`),
    )
    error.status = response.status
    throw error
  }
  return data
}

function getObjectName(metadata) {
  return metadata?.name || metadata?.objectName
}

function fieldsFor(metadata, mode) {
  const fields = metadata?.[mode === 'view' ? 'fields' : `${mode}Fields`] || metadata?.fields || []
  return Array.isArray(fields) ? fields : Object.keys(fields).map((name) => ({ name }))
}

function fieldName(field) {
  return typeof field === 'string' ? field : field.name || field.fieldName
}

function fieldLabel(name) {
  return name.replace(/([a-z])([A-Z])/g, '$1 $2')
}

function fieldType(field, name) {
  const type = String(typeof field === 'object' ? field.type || field.dataType || '' : '').toLowerCase()
  if (type.includes('date') || name === 'CloseDate') return 'date'
  if (type.includes('email') || name.toLowerCase().includes('email')) return 'email'
  if (type.includes('url') || name === 'Website') return 'url'
  if (type.includes('double') || type.includes('currency') || type.includes('percent') || ['Amount', 'Probability'].includes(name)) return 'number'
  return 'text'
}

function App() {
  const [connected, setConnected] = useState(false)
  const [connectionLoading, setConnectionLoading] = useState(true)
  const [connecting, setConnecting] = useState(false)
  const [metadata, setMetadata] = useState([])
  const [selectedObject, setSelectedObject] = useState('Account')
  const [records, setRecords] = useState([])
  const [nextCursor, setNextCursor] = useState(null)
  const [hasMore, setHasMore] = useState(false)
  const [loadingRecords, setLoadingRecords] = useState(false)
  const [loadingMore, setLoadingMore] = useState(false)
  const [search, setSearch] = useState('')
  const [notice, setNotice] = useState(null)
  const [modal, setModal] = useState(null)
  const [activeRecord, setActiveRecord] = useState(null)
  const [sidebarOpen, setSidebarOpen] = useState(false)
  const sentinelRef = useRef(null)
  const loadingRef = useRef(false)
  const popupRef = useRef(null)

  const objectMetadata = useMemo(
    () => metadata.find((item) => getObjectName(item) === selectedObject) || { name: selectedObject, fields: DISPLAY_FIELDS[selectedObject] },
    [metadata, selectedObject],
  )
  const visibleFields = useMemo(() => {
    const configured = fieldsFor(objectMetadata, 'view').map(fieldName)
    return (DISPLAY_FIELDS[selectedObject] || configured).filter((name) => configured.length === 0 || configured.includes(name))
  }, [objectMetadata, selectedObject])
  const filteredRecords = useMemo(() => {
    const query = search.trim().toLowerCase()
    if (!query) return records
    return records.filter((record) => visibleFields.some((field) => String(record[field] ?? '').toLowerCase().includes(query)))
  }, [records, search, visibleFields])

  const showNotice = useCallback((type, message) => {
    setNotice({ type, message })
    window.setTimeout(() => setNotice(null), 5000)
  }, [])

  const loadMetadata = useCallback(async () => {
    const data = await request('/api/salesforce/objects')
    const list = Array.isArray(data) ? data : data?.objects || data?.data || []
    setMetadata(list.filter((item) => ALLOWED_OBJECTS.includes(getObjectName(item))))
  }, [])

  const checkStatus = useCallback(async () => {
    const data = await request('/api/salesforce/status')
    const isConnected = Boolean(data?.connected)
    setConnected(isConnected)
    return isConnected
  }, [])

  useEffect(() => {
    checkStatus().then((isConnected) => {
      if (isConnected) loadMetadata().catch((error) => showNotice('danger', error.message))
    }).catch(() => setConnected(false)).finally(() => setConnectionLoading(false))
  }, [checkStatus, loadMetadata, showNotice])

  const loadRecords = useCallback(async (cursor = null, append = false) => {
    if (loadingRef.current || !selectedObject) return
    loadingRef.current = true
    append ? setLoadingMore(true) : setLoadingRecords(true)
    try {
      const query = cursor ? `?cursor=${encodeURIComponent(cursor)}` : ''
      const data = await request(`/api/salesforce/objects/${selectedObject}/records${query}`)
      const page = Array.isArray(data) ? data : data?.records || data?.data || []
      setRecords((current) => append ? [...current, ...page] : page)
      setNextCursor(data?.nextCursor || null)
      setHasMore(Boolean(data?.hasMore && data?.nextCursor))
    } catch (error) {
      if (error.status === 401) setConnected(false)
      showNotice('danger', error.message)
    } finally {
      loadingRef.current = false
      setLoadingRecords(false)
      setLoadingMore(false)
    }
  }, [selectedObject, showNotice])

  useEffect(() => {
    setRecords([])
    setNextCursor(null)
    setHasMore(false)
    if (connected) loadRecords()
  }, [connected, selectedObject, loadRecords])

  useEffect(() => {
    const sentinel = sentinelRef.current
    if (!sentinel) return undefined
    const observer = new IntersectionObserver(([entry]) => {
      if (entry.isIntersecting && hasMore && nextCursor && !loadingRef.current) loadRecords(nextCursor, true)
    }, { rootMargin: '240px' })
    observer.observe(sentinel)
    return () => observer.disconnect()
  }, [hasMore, nextCursor, loadRecords])

  const connect = () => {
    const popup = window.open(`${API_BASE}/api/auth/login`, 'salesforce-login', 'width=560,height=720,left=200,top=100')
    if (!popup) {
      showNotice('warning', 'Popup was blocked. Allow popups for this site and try again.')
      return
    }
    popupRef.current = popup
    setConnecting(true)
    const started = Date.now()
    const interval = window.setInterval(async () => {
      try {
        const isConnected = await checkStatus()
        if (isConnected) {
          window.clearInterval(interval)
          if (!popup.closed) popup.close()
          setConnecting(false)
          await loadMetadata()
          showNotice('success', 'Salesforce connected successfully.')
        } else if (popup.closed || Date.now() - started > 120000) {
          window.clearInterval(interval)
          setConnecting(false)
          if (!popup.closed) popup.close()
          if (Date.now() - started > 120000) showNotice('warning', 'Salesforce login timed out. Please try again.')
        }
      } catch {
        if (Date.now() - started > 120000) {
          window.clearInterval(interval)
          setConnecting(false)
          showNotice('danger', 'Unable to verify the Salesforce connection.')
        }
      }
    }, 1500)
  }

  const csrfRequest = async () => {
    const token = await request('/api/auth/csrf')
    return { [token.headerName || 'X-XSRF-TOKEN']: token.token }
  }

  const mutate = async (method, path, body) => {
    let csrf = await csrfRequest()
    const options = { method, headers: { ...csrf, 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined }
    try {
      return await request(path, options)
    } catch (error) {
      if (error.status === 403) {
        csrf = await csrfRequest()
        return request(path, { ...options, headers: { ...options.headers, ...csrf } })
      }
      throw error
    }
  }

  const openForm = (record = null) => {
    const mode = record ? 'edit' : 'create'
    const fields = fieldsFor(objectMetadata, mode).map(fieldName).filter(Boolean)
    const values = fields.reduce((result, name) => ({ ...result, [name]: record?.[name] ?? '' }), {})
    setActiveRecord(record)
    setModal({ type: mode, fields, values })
  }

  const saveRecord = async (event) => {
    event.preventDefault()
    const formData = new FormData(event.currentTarget)
    const values = Object.fromEntries(formData.entries())
    if (!Object.values(values).some(Boolean)) {
      showNotice('warning', 'Enter at least one value before saving.')
      return
    }
    try {
      if (modal.type === 'create') await mutate('POST', `/api/salesforce/objects/${selectedObject}/records`, values)
      else await mutate('PATCH', `/api/salesforce/objects/${selectedObject}/records/${activeRecord.Id || activeRecord.id}`, values)
      setModal(null)
      await loadRecords()
      showNotice('success', `${selectedObject} ${modal.type === 'create' ? 'created' : 'updated'} successfully.`)
    } catch (error) {
      showNotice('danger', error.message)
    }
  }

  const viewRecord = async (record) => {
    try {
      const id = record.Id || record.id
      const data = await request(`/api/salesforce/objects/${selectedObject}/records/${encodeURIComponent(id)}`)
      setActiveRecord(data?.record || data)
      setModal({ type: 'view' })
    } catch (error) {
      showNotice('danger', error.message)
    }
  }

  const deleteRecord = async (record) => {
    const id = record.Id || record.id
    if (!window.confirm(`Delete this ${selectedObject.toLowerCase()}? This action cannot be undone.`)) return
    try {
      await mutate('DELETE', `/api/salesforce/objects/${selectedObject}/records/${encodeURIComponent(id)}`)
      setRecords((current) => current.filter((item) => (item.Id || item.id) !== id))
      showNotice('success', `${selectedObject} deleted successfully.`)
    } catch (error) {
      showNotice('danger', error.message)
    }
  }

  const logout = () => {
    setConnected(false)
    setRecords([])
    showNotice('info', 'Session cleared in this window. Reconnect to Salesforce to continue.')
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand"><div className="brand-mark"><ShieldCheck size={20} /></div><div><strong>Cloudbase</strong><span>Salesforce CRM</span></div></div>
        <div className="topbar-actions">
          <div className={`connection-pill ${connected ? 'is-connected' : ''}`}><span className="status-dot" />{connected ? 'Connected' : 'Not connected'}</div>
          {connected ? <button className="btn btn-ghost" onClick={logout}><LogOut size={16} /> Disconnect</button> : <button className="btn btn-primary" onClick={connect} disabled={connecting || connectionLoading}>{connecting ? <LoaderCircle className="spin" size={16} /> : <ShieldCheck size={16} />}{connecting ? 'Connecting…' : 'Connect Salesforce'}</button>}
        </div>
      </header>
      <div className="layout">
        <aside className={`sidebar ${sidebarOpen ? 'open' : ''}`}>
          <div className="sidebar-heading">Workspace</div>
          <button className="nav-item active"><LayoutDashboard size={17} /> Overview</button>
          <div className="sidebar-heading object-heading">Salesforce objects</div>
          {ALLOWED_OBJECTS.map((name) => {
            const Icon = icons[name]
            return <button key={name} className={`nav-item ${selectedObject === name ? 'selected' : ''}`} onClick={() => { setSelectedObject(name); setSidebarOpen(false) }}><Icon size={17} /> {name}{selectedObject === name && <ChevronDown className="nav-chevron" size={15} />}</button>
          })}
          <div className="sidebar-footer"><CircleHelp size={16} /><span>Connected data stays<br />in your Salesforce org.</span></div>
        </aside>
        <main className="main-content">
          <div className="mobile-toolbar"><button className="icon-btn" onClick={() => setSidebarOpen((open) => !open)}><Menu size={20} /></button><span>Objects</span></div>
          <div className="page-header"><div><div className="eyebrow">Workspace / {selectedObject}</div><h1>{selectedObject}s</h1><p>Manage your Salesforce {selectedObject.toLowerCase()} records.</p></div><button className="btn btn-primary" onClick={() => openForm()} disabled={!connected}><Plus size={17} /> New {selectedObject}</button></div>
          {notice && <div className={`notice notice-${notice.type}`}><span>{notice.type === 'success' ? <Check size={17} /> : <AlertCircle size={17} />}</span>{notice.message}<button className="notice-close" onClick={() => setNotice(null)}><X size={15} /></button></div>}
          <section className="stats-row"><div className="stat-card"><div className="stat-icon blue"><Database size={18} /></div><div><span>Active object</span><strong>{selectedObject}</strong></div></div><div className="stat-card"><div className="stat-icon green"><Check size={18} /></div><div><span>Connection</span><strong>{connected ? 'Salesforce live' : 'Awaiting connection'}</strong></div></div><div className="stat-card"><div className="stat-icon purple"><FileText size={18} /></div><div><span>Records loaded</span><strong>{records.length}</strong></div></div></section>
          <section className="records-card">
            <div className="card-toolbar"><div><h2>All {selectedObject.toLowerCase()} records</h2><span className="record-count">{records.length}{hasMore ? '+' : ''} records loaded</span></div><div className="toolbar-actions"><div className="search-box"><Search size={16} /><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Search records…" aria-label="Search records" /></div><button className="icon-btn" onClick={() => loadRecords()} disabled={loadingRecords} title="Refresh"><RefreshCw className={loadingRecords ? 'spin' : ''} size={17} /></button></div></div>
            {!connected ? <div className="empty-state"><div className="empty-icon"><ShieldCheck size={25} /></div><h3>Connect to Salesforce</h3><p>Connect your Salesforce account to load and manage live records.</p><button className="btn btn-primary" onClick={connect}><ShieldCheck size={16} /> Connect Salesforce</button></div> : loadingRecords ? <div className="loading-state"><LoaderCircle className="spin" size={26} /><span>Loading records…</span></div> : filteredRecords.length === 0 ? <div className="empty-state"><div className="empty-icon"><Search size={24} /></div><h3>{search ? 'No matching records' : `No ${selectedObject.toLowerCase()} records yet`}</h3><p>{search ? 'Try a different search term.' : 'Create your first record to get started.'}</p>{!search && <button className="btn btn-primary" onClick={() => openForm()}><Plus size={16} /> Create record</button>}</div> : <><div className="table-wrap"><table><thead><tr>{visibleFields.map((field) => <th key={field}>{fieldLabel(field)}</th>)}<th className="actions-heading">Actions</th></tr></thead><tbody>{filteredRecords.map((record, index) => <tr key={record.Id || record.id || index}>{visibleFields.map((field) => <td key={field}>{String(record[field] ?? '—')}</td>)}<td><div className="row-actions"><button className="row-btn" onClick={() => viewRecord(record)} title="View"><Eye size={16} /></button><button className="row-btn" onClick={() => openForm(record)} title="Edit"><Pencil size={16} /></button><button className="row-btn danger" onClick={() => deleteRecord(record)} title="Delete"><Trash2 size={16} /></button></div></td></tr>)}</tbody></table></div><div ref={sentinelRef} className="load-more">{loadingMore && <><LoaderCircle className="spin" size={17} /> Loading more records…</>}{!loadingMore && !hasMore && <span>You've reached the end of the list</span>}</div></>}
          </section>
        </main>
      </div>
      {modal?.type === 'view' && <div className="modal-backdrop" onMouseDown={(event) => event.target === event.currentTarget && setModal(null)}><div className="modal-card"><div className="modal-header"><div><span className="eyebrow">Record details</span><h2>{selectedObject}</h2></div><button className="icon-btn" onClick={() => setModal(null)}><X size={18} /></button></div><div className="detail-grid">{visibleFields.map((field) => <div className="detail-item" key={field}><span>{fieldLabel(field)}</span><strong>{String(activeRecord?.[field] ?? '—')}</strong></div>)}</div></div></div>}
      {(modal?.type === 'create' || modal?.type === 'edit') && <div className="modal-backdrop" onMouseDown={(event) => event.target === event.currentTarget && setModal(null)}><form className="modal-card form-modal" onSubmit={saveRecord}><div className="modal-header"><div><span className="eyebrow">{modal.type === 'create' ? 'Create record' : 'Edit record'}</span><h2>{modal.type === 'create' ? `New ${selectedObject}` : `Edit ${selectedObject}`}</h2></div><button type="button" className="icon-btn" onClick={() => setModal(null)}><X size={18} /></button></div><div className="form-grid">{modal.fields.map((field) => <label key={field}>{fieldLabel(field)}<input name={field} type={fieldType(objectMetadata.fields?.find?.((item) => fieldName(item) === field), field)} defaultValue={modal.values[field]} readOnly={selectedObject === 'Case' && field === 'CaseNumber'} required={false} /></label>)}</div><div className="modal-footer"><button type="button" className="btn btn-ghost" onClick={() => setModal(null)}>Cancel</button><button className="btn btn-primary" type="submit"><Check size={16} /> Save {selectedObject}</button></div></form></div>}
    </div>
  )
}

export default App
