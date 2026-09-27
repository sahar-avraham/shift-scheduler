import { useEffect, useState } from 'react'
import { api } from '../api/client'
import ShiftGrid from '../components/ShiftGrid'
import WeekPicker from '../components/WeekPicker'
import ShiftCell from '../components/ShiftCell'
import PublishDialog from '../components/PublishDialog'
import AssignmentPanel from '../components/AssignmentPanel'

const STATUS_LABELS = {
    COLLECTING: 'פתוח להגשת אילוצים',
    DRAFT: 'טיוטה',
    SOLVING: 'בונה סידור…',
    PUBLISHED: 'פורסם'
}

export default function ScheduleBuilderPage() {
    const [weeks, setWeeks] = useState([])
    const [week, setWeek] = useState(null)
    const [detail, setDetail] = useState(null)
    const [coverage, setCoverage] = useState([])
    const [positions, setPositions] = useState([])

    const [selected, setSelected] = useState(new Set())
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState(null)
    const [busy, setBusy] = useState(false)
    const [publishing, setPublishing] = useState(false)

    const [solving, setSolving] = useState(false)
    const [solveResult, setSolveResult] = useState(null)

    useEffect(() => {
        Promise.all([api.get('/api/schedules'), api.get('/api/job-positions')])
            .then(([weekList, positionList]) => {
                setWeeks(weekList)
                setWeek(weekList[0] ?? null)
                setPositions(positionList)
            })
            .catch(() => setError('לא הצלחנו לטעון את הנתונים'))
            .finally(() => setLoading(false))
    }, [])

    useEffect(() => {
        if (week) {
            void loadWeek()
        }
    }, [week])

    // solve() waits for the solver. Nothing is waiting when the page is opened
    // or refreshed in the middle of one, so this does it instead.
    useEffect(() => {
        if (detail?.status === 'SOLVING' && !solving) {
            setSolving(true)
            waitForSolver()
                .then(() => loadWeek(true))
                .catch((err) => setError(messageFor(err)))
                .finally(() => setSolving(false))
        }
    }, [detail?.status])

    // Reloading clears the selected shifts. The reloads that follow an action on
    // those same shifts pass true, so the panel stays open.
    async function loadWeek(keepSelection = false) {
        if (!keepSelection) {
            setSelected(new Set())
        }

        setSolveResult(null)

        try {
            const [detailData, coverageData] = await Promise.all([
                api.get(`/api/schedules/${week.id}`),
                api.get(`/api/schedules/${week.id}/coverage`),
            ])

            setDetail(detailData)
            setCoverage(coverageData)
            setError(null)
        } catch {
            setError('לא הצלחנו לטעון את הסידור')
        }
    }

    // Escape and clicking away clear the selection.
    useEffect(() => {
        if (selected.size === 0) {
            return
        }

        function onClickAway(event) {
            if (!event.target.closest('.grid') && !event.target.closest('.panel')) {
                setSelected(new Set())
            }
        }

        function onKey(event) {
            if (event.key === 'Escape' && !document.querySelector('.modal-backdrop')) {
                setSelected(new Set())
            }
        }

        window.addEventListener('mousedown', onClickAway)
        window.addEventListener('keydown', onKey)

        return () => {
            window.removeEventListener('mousedown', onClickAway)
            window.removeEventListener('keydown', onKey)
        }
    }, [selected.size])

    // The list comes back newest first, so the next week is a week after the
    // first entry.
    async function createWeek() {
        const next = weeks.length === 0
            ? nextSunday()
            : addDays(weeks[0].weekStart, 7)

        setBusy(true)

        try {
            const created = await api.post('/api/schedules', { weekStart: next })
            const list = await api.get('/api/schedules')

            setWeeks(list)
            setWeek(list.find((candidate) => candidate.id === created.id) ?? list[0])
        } catch (err) {
            setError(messageForCreate(err))
        } finally {
            setBusy(false)
        }
    }

    // Requirements are replaced per shift, so applying to a selection means one
    // call each. Every one of them raises the schedule version by one, and the
    // next call has to send the new number.
    async function applyRequirements(counts) {
        setBusy(true)

        try {
            const body = Object.entries(counts)
                .map(([jobPositionId, value]) => ({
                    jobPositionId: Number(jobPositionId),
                    requiredCount: Number(value.count) || 0,
                    essential: value.essential,
                }))

            let version = detail.version

            for (const shiftId of selected) {
                await api.put(
                    `/api/schedules/${week.id}/shifts/${shiftId}/requirements`,
                    { version, requirements: body })

                version++
            }

            await loadWeek(true)
        } catch (err) {
            setError(err.code === 'STALE_VERSION'
                ? 'הסידור השתנה במקביל. רענן ונסה שוב'
                : 'שמירת הדרישות נכשלה')
        } finally {
            setBusy(false)
        }
    }

    async function clearShiftAssignments() {
        if (!confirm(`לאפס את השיבוצים של ${selected.size} משמרות?`)) return
        await act(() => api.post(`/api/schedules/${week.id}/assignments/clear`,
            { version: detail.version, shiftIds: selectedIds }))
    }

    async function clearShiftRequirements() {
        if (!confirm(`לאפס את דרישות האיוש של ${selected.size} משמרות?`)) return
        await act(() => api.post(`/api/schedules/${week.id}/requirements/clear`,
            { version: detail.version, shiftIds: selectedIds }))
    }

    // Clears the whole week
    async function clearWholeWeek() {
        if (!confirm('לאפס את כל השיבוצים בשבוע?')) return
        await act(() => api.delete(
            `/api/schedules/${week.id}/assignments?version=${detail.version}`))
    }

    async function lock() {
        await act(() => api.put(`/api/schedules/${week.id}/lock`,
            { version: detail.version }))
    }

    async function publish() {
        await act(() => api.put(`/api/schedules/${week.id}/publish`,
            { version: detail.version }))
    }

    // Only mails the people whose shifts the manager touched since the week went
    // out. The roster itself already shows the change, so this is the notice.
    //
    // The mails go out on a queue, and the waiting list is cleared once they are
    // sent, so the reply still carries the old count. Reloading a moment later
    // picks up the real one. Clicking twice is refused by the server either way.
    async function republish() {
        if (!confirm('לאחר פרסום מחדש כל העובדים שמשמרותיהם שונו יקבלו הודעה במייל.')) return

        await act(() => api.put(`/api/schedules/${week.id}/republish`,
            { version: detail.version }))

        await pause(500)
        await loadWeek(true)
    }

    // The request comes back immediately and the solver carries on in the
    // background, so the screen asks every second whether it's finished. Since timefold
    // can backtrack and unassign employees, not showing assignment progress.
    // Instead, shows loading / working to show that the solver is currently working.
    async function solve() {
        setSolving(true)
        setSolveResult(null)
        setError(null)

        try {
            await api.post(`/api/schedules/${week.id}/solve`)
            await waitForSolver()

            const fresh = await api.get(`/api/schedules/${week.id}/coverage`)

            setCoverage(fresh)
            setDetail(await api.get(`/api/schedules/${week.id}`))
            setSolveResult(summarise(fresh))
        } catch (err) {
            setError(messageFor(err))
        } finally {
            setSolving(false)
        }
    }

    // NOT_SOLVING comes back both before the solver has picked the job up and
    // after it's done, so the first check waits a moment.
    async function waitForSolver() {
        await pause(700)

        for (let attempt = 0; attempt < 120; attempt++) {
            const status = await api.get(`/api/schedules/${week.id}/solve-status`)

            if (!status.solving) {
                // The solver failed on its own thread, so the failure only
                // shows up here and not on the solve request itself.
                if (status.error) {
                    throw Object.assign(new Error(status.error), { code: 'SOLVE_FAILED' })
                }

                return
            }

            await pause(1000)
        }

        throw new Error('Solver did not finish in time')
    }


    // Every action that changes the week goes through here. It reloads the week
    // and the list, so the version and the status are never stale.
    async function act(call) {
        setBusy(true)

        try {
            await call()
            await loadWeek()

            setWeeks(await api.get('/api/schedules'))
        } catch (err) {
            setError(messageFor(err))
        } finally {
            setBusy(false)
        }
    }

    if (loading) {
        return <p className="notice">טוען…</p>
    }

    const byShiftId = new Map(coverage.map((entry) => [entry.shiftId, entry]))
    const collecting = detail?.status === 'COLLECTING'
    // True while this screen is waiting, and also when the week was already
    // solving when the page opened.
    const isSolving = solving || detail?.status === 'SOLVING'
    // The cards read straight off coverage, so they always agree with the grid
    // underneath them.
    const totals = summarise(coverage)

    const weekAssignedCount = coverage
        .reduce((n, entry) => n + entry.assignments.length, 0)

    const selectedShifts = detail
        ? detail.shifts.filter((shift) => selected.has(shift.id))
        : []

    const selectedIds = [...selected]
    const assignedCount = selectedIds
        .reduce((n, id) => n + (byShiftId.get(id)?.assignments.length ?? 0), 0)
    const requirementCount = selectedShifts
        .reduce((n, s) => n + s.requirements.length, 0)

    return (
        <>
            <div className="page-head">
                <h1>בניית סידור</h1>

                {/* The buttons are laid out this way so they stay in the same
                    place when paging between weeks in different statuses. */}
                <div className="head-actions">
                    {collecting && (
                        <button className="secondary" onClick={lock} disabled={busy}>
                            סגירת הגשת אילוצים
                        </button>
                    )}

                    {detail?.status === 'DRAFT' && (
                        <button onClick={() => setPublishing(true)}
                                disabled={busy || isSolving}>פרסום</button>
                    )}

                    {/* Only on once somebody is actually waiting to be told - the
                        server rejects an empty republish anyway. */}
                    {detail?.status === 'PUBLISHED' && (
                        <button onClick={republish}
                                disabled={busy || !(detail.pendingChanges > 0)}>
                            פרסום מחדש
                        </button>
                    )}

                    <button className="secondary" onClick={createWeek} disabled={busy || isSolving}>
                        שבוע חדש
                    </button>
                </div>
            </div>

            {weeks.length === 0 ? (
                <p className="notice">אין עדיין שבועות. התחל בלחיצה על "שבוע חדש".</p>
            ) : (
                <>
                    {coverage.length > 0 && (
                        <div className="summary">
                            <div className="summary-card">
                                <div className="summary-label">שיבוצים</div>
                                <div className="summary-figure">
                                    <span className="summary-number">{totals.filled}</span>
                                    <span className="summary-note">מתוך {totals.required}</span>
                                </div>
                                <div className="summary-bar">
                                    <span style={{ width: `${percent(totals.filled, totals.required)}%` }} />
                                </div>
                            </div>

                            <div className="summary-card">
                                <div className="summary-label">מקומות חסרים</div>
                                <div className="summary-figure">
                                    <span className="summary-number is-warn">{totals.missing}</span>
                                    <span className="summary-note">ב־{totals.shiftsWithGaps} משמרות</span>
                                </div>
                                <div className="summary-bar">
                                    <span className="is-warn"
                                          style={{ width: `${percent(totals.missing, totals.required)}%` }} />
                                </div>
                            </div>

                            <div className="summary-card is-critical">
                                <div className="summary-label">תפקידים חיוניים ללא איוש</div>
                                <div className="summary-figure">
                                    <span className="summary-number is-critical">{totals.deserted}</span>
                                    <span className="summary-note">ב־{totals.desertedShifts} משמרות</span>
                                </div>
                                <div className="summary-bar">
                                    <span className="is-critical"
                                          style={{ width: `${percent(totals.deserted, totals.required)}%` }} />
                                </div>
                            </div>
                        </div>
                    )}

                    <div className="week-row">
                        <WeekPicker weeks={weeks} current={week} onChange={setWeek}>
                            <span className="week-status">{STATUS_LABELS[detail?.status] ?? ''}</span>
                        </WeekPicker>

                        <div className="week-actions">
                            {(detail?.status === 'DRAFT' || isSolving) && (
                                <button className="solve-button" disabled={isSolving || busy} onClick={solve}>
                                    {isSolving ? 'בונה סידור…' : 'בנייה אוטומטית'}
                                </button>
                            )}
                            {detail?.status === 'DRAFT' && (
                                <button className="danger" onClick={clearWholeWeek}
                                        disabled={busy || isSolving || weekAssignedCount === 0}>
                                    איפוס כל השיבוצים
                                </button>
                            )}
                        </div>
                    </div>

                    {solveResult && (
                        <p className={`solve-result ${severity(solveResult)}`}>
                            הסידור נבנה · {solveResult.filled} שיבוצים

                            {solveResult.missing === 0 && ' · כל המשמרות מאוישות'}

                            {solveResult.missing > 0
                                && ` · ${solveResult.missing} מקומות נותרו חסרים`}

                            {solveResult.deserted > 0
                                && ` · ${solveResult.deserted} תפקידים חיוניים ללא איוש`}
                        </p>
                    )}


                    {/* A published week can still be fixed by hand, so this says
                        who has not been told yet. */}
                    {detail?.pendingChanges > 0 && (
                        <p className="solve-result has-gaps">
                            הסידור שונה מאז הפרסום · {detail.pendingChanges} עובדים
                            ממתינים להודעה
                        </p>
                    )}

                    {error && <p className="error">{error}</p>}

                    {detail && (
                        <ShiftGrid
                            shifts={detail.shifts.map(toGridShift)}
                            weekStart={detail.weekStart}
                            selected={selected}
                            onSelectionChange={setSelected}
                            renderCell={(shift) => (
                                <ShiftCell coverage={byShiftId.get(shift.shiftId)} collecting={collecting} />
                            )}
                        />
                    )}

                    {selected.size > 0 && (
                        <div className="panel">
                            <div className="panel-head">
                                <strong>
                                    {selected.size === 1
                                        ? describe(selectedShifts[0])
                                        : `${selected.size} משמרות נבחרו`}
                                </strong>

                                {/* Only on a draft. A published week is changed one
                                    shift at a time. */}
                                <button className="danger" onClick={clearShiftAssignments}
                                        disabled={busy || isSolving || assignedCount === 0 || detail.status !== 'DRAFT'}>
                                    איפוס שיבוצים
                                </button>
                            </div>

                            {/* Who is on the shift on one side, what it needs on
                                the other. Each side is wrapped because the panel
                                renders more than one element, and the grid needs
                                one child per column. */}
                            <div className="panel-split">
                                {/* One shift at a time, and not while constraints are
                                    still being collected. */}
                                {selected.size === 1 && detail.status !== 'COLLECTING' && !isSolving && (
                                    <div>
                                        <AssignmentPanel
                                            shift={selectedShifts[0]}
                                            coverage={byShiftId.get(selectedShifts[0].id)}
                                            scheduleVersion={detail.version}
                                            onChanged={() => loadWeek(true)}
                                            onError={setError}
                                        />
                                    </div>
                                )}

                                {detail.status !== 'PUBLISHED' && (
                                    <div>
                                        <RequirementFields
                                            shifts={selectedShifts}
                                            positions={positions}
                                            busy={busy || isSolving}
                                            canClear={requirementCount > 0 && detail.status !== 'PUBLISHED'}
                                            onApply={applyRequirements}
                                            onClear={clearShiftRequirements}
                                        />
                                    </div>
                                )}
                            </div>
                        </div>
                    )}


                    <div className="legend">
                        <span><i />מאויש</span>
                        <span><i className="is-warn" />חסר חלק מהאיוש</span>
                        <span><i className="is-critical" />תפקיד חיוני ללא איוש כלל</span>

                        <span className="legend-keys">
                            לחיצה בוחרת · גרירה בוחרת כמה · Esc מבטל
                        </span>
                    </div>
                </>
            )}
            {publishing && (
                <PublishDialog
                    coverage={coverage}
                    busy={busy}
                    onClose={() => setPublishing(false)}
                    onConfirm={async () => {
                        await publish()
                        setPublishing(false)
                    }}
                />
            )}
        </>
    )
}
// Each position carries a count and whether the shift can run without it.
function RequirementFields({ shifts, positions, busy, canClear, onApply, onClear }) {
    const [values, setValues] = useState({})

    // A new selection brings its own numbers. Where the selected shifts differ
    // the field is left blank rather than showing one of them.
    //
    // Compares the ids and not the array. A new array is built each time and
    // would always look changed, so the fields would refill while in use.
    useEffect(() => {
        const next = {}

        for (const position of positions) {
            const found = shifts.map((shift) => requirementFor(shift, position.id))

            const counts = found.map((requirement) => requirement.count)
            const flags = found.map((requirement) => requirement.essential)

            next[position.id] = {
                count: counts.every((count) => count === counts[0]) ? counts[0] : '',
                essential: flags.every((flag) => flag === flags[0]) ? flags[0] : true,
            }
        }

        setValues(next)
    }, [shifts.map((shift) => shift.id).join(), positions])

    function set(positionId, changes) {
        setValues((current) => ({
            ...current,
            [positionId]: { ...current[positionId], ...changes },
        }))
    }

    return (
        <div className="requirements">
            <span className="field-label">דרישות איוש</span>

            <div className="requirement-fields">
                {/* Not a Field: the name, the checkbox and the number are three
                    separate items on one row, so the name can take the slack and
                    the other two line up down the list whatever the name's
                    length. */}
                {positions.map((position) => (
                    <div key={position.id} className="requirement-field">
                        <span className="field-label" title={position.name}>{position.name}</span>

                        <label className="checkbox">
                            <input
                                type="checkbox"
                                checked={values[position.id]?.essential ?? true}
                                disabled={busy || !Number(values[position.id]?.count)}
                                onChange={(e) => set(position.id, { essential: e.target.checked })}
                            />
                            חיוני
                        </label>

                        <input
                            type="number"
                            min={0}
                            max={50}
                            value={values[position.id]?.count ?? ''}
                            disabled={busy}
                            onChange={(e) => set(position.id, { count: e.target.value })}
                        />
                    </div>
                ))}
            </div>

            <p className="hint hint-quiet">
                * תפקיד חיוני שנשאר ללא איוש כלל מסומן באדום, והמנוע יעדיף לאייש אותו
                על פני תפקיד שאינו חיוני.
            </p>

            <div className="form-actions">
                <button onClick={() => onApply(values)} disabled={busy}>
                    {busy ? 'שומר…' : 'שמירת דרישות'}
                </button>

                <button className="danger" onClick={onClear} disabled={busy || !canClear}>
                    איפוס דרישות איוש
                </button>
            </div>
        </div>
    )
}

// The grid works in the shape my-week uses, so the manager's detail response
// is mapped onto it rather than the grid learning a second one.
function toGridShift(shift) {
    return {
        shiftId: shift.id,
        shiftDate: shift.shiftDate,
        shiftTypeName: shift.shiftTypeName,
        startTime: shift.startTime,
        endTime: shift.endTime,
    }
}

// A position with no requirement on this shift counts as zero.
function requirementFor(shift, jobPositionId) {
    const found = shift.requirements.find(
        (requirement) => requirement.jobPositionId === jobPositionId)

    return found
        ? { count: String(found.requiredCount), essential: found.essential }
        : { count: '0', essential: true }
}

function describe(shift) {
    const [year, month, day] = shift.shiftDate.split('-')
    return `${shift.shiftTypeName} · ${Number(day)}/${Number(month)}/${year}`
}

// Today is local, the days are added in UTC so a clock change can't move them.
function nextSunday() {
    const now = new Date()
    const daysAhead = (7 - now.getDay()) % 7 || 7

    return new Date(Date.UTC(now.getFullYear(), now.getMonth(), now.getDate() + daysAhead))
        .toISOString().slice(0, 10)
}

// UTC so the daylight saving switch can't push the week off a Sunday.
function addDays(iso, days) {
    const [year, month, day] = iso.split('-').map(Number)

    return new Date(Date.UTC(year, month - 1, day + days))
        .toISOString().slice(0, 10)
}

function messageForCreate(error) {
    if (error.code === 'NO_SHIFT_TYPES') {
        return 'צריך להגדיר סוגי משמרת לפני שאפשר לפתוח שבוע'
    }

    if (error.code === 'WRONG_STATUS') {
        return 'לא ניתן לפתוח שבוע בזמן בניית סידור'
    }

    if (error.status === 409) {
        return 'כבר קיים סידור לשבוע הזה'
    }

    return 'יצירת השבוע נכשלה'
}

function messageFor(error) {
    if (error.code === 'STALE_VERSION') {
        return 'הסידור השתנה במקביל. רענן ונסה שוב'
    }

    if (error.code === 'WRONG_STATUS') {
        return 'הפעולה אינה אפשרית במצב הנוכחי של הסידור'
    }

    if (error.code === 'SOLVE_FAILED') {
        return 'בניית הסידור נכשלה. אפשר לנסות שוב'
    }

    return 'הפעולה נכשלה'
}

function pause(millis) {
    return new Promise((resolve) => setTimeout(resolve, millis))
}

// Summary of the solver work - assigns and unassigned.
// Counts both what the summary cards show and what the message after a solve
// says. deserted counts one per shift-and-position pair, so it can be higher
// than the number of shifts - the cards say "in N shifts" next to it.
function summarise(coverage) {
    let filled = 0
    let required = 0
    let missing = 0
    let deserted = 0
    let shiftsWithGaps = 0
    let desertedShifts = 0

    for (const shift of coverage) {
        let hasGap = false
        let hasDeserted = false

        for (const position of shift.positions) {
            filled += position.assigned
            required += position.required
            missing += position.missing

            if (position.missing > 0) {
                hasGap = true
            }

            if (position.essential && position.required > 0 && position.assigned === 0) {
                deserted++
                hasDeserted = true
            }
        }

        if (hasGap) {
            shiftsWithGaps++
        }

        if (hasDeserted) {
            desertedShifts++
        }
    }

    return { filled, required, missing, deserted, shiftsWithGaps, desertedShifts }
}

function percent(part, whole) {
    return whole === 0 ? 0 : Math.round((part / whole) * 100)
}

function severity(result) {
    if (result.deserted > 0) {
        return 'is-deserted'
    }

    return result.missing > 0 ? 'has-gaps' : ''
}