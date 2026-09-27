import { useEffect, useRef, useState } from 'react'
import { api } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import ShiftGrid from '../components/ShiftGrid'
import WeekPicker from '../components/WeekPicker'

const TYPES = [
    { value: null, label: 'יכול' },
    { value: 'PREFERS_NOT', label: 'מעדיף שלא' },
    { value: 'CANNOT', label: 'לא יכול' },
]

const LABELS = {
    PREFERS_NOT: 'מעדיף שלא',
    CANNOT: 'לא יכול',
}

// The picker's value when the manager is looking at everyone rather than one
// person's week.
const ALL = 'all'

// An employee sees their own week; a manager picks whose week to look at, which
// is how constraints get corrected once the submission window has closed.
export default function ConstraintsPage() {
    const { user, isManager } = useAuth()

    const [weeks, setWeeks] = useState([])
    const [week, setWeek] = useState(null)
    const [employees, setEmployees] = useState([])

    const [employeeId, setEmployeeId] = useState(isManager ? ALL : user.employeeId)
    const [allPreferences, setAllPreferences] = useState([])

    const [myWeek, setMyWeek] = useState(null)
    const [selected, setSelected] = useState(new Set())
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState(null)
    const [busy, setBusy] = useState(false)

    useEffect(() => {
        const calls = [api.get('/api/schedules')]

        // The employee list is only loaded for a manager. It fills the picker.
        if (isManager) {
            calls.push(api.get('/api/employees'))
        }

        Promise.all(calls)
            .then(([weekList, employeeList]) => {
                setWeeks(weekList)
                setWeek(weekList[0] ?? null)

                if (employeeList) {
                    setEmployees(employeeList.filter((employee) => employee.active))
                }
            })
            .catch(() => setError('לא הצלחנו לטעון את הנתונים'))
            .finally(() => setLoading(false))
    }, [isManager])

    // Reloads when the week changes, and when the manager picks a
    // different employee.
    useEffect(() => {
        if (week) {
            loadWeek()
        }
    }, [week, employeeId])

    // Clicking away or pressing Escape drops the selection. However, the grid and the
    // panel are excluded - pressing inside won't cancel the selection
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
            if (event.key === 'Escape') {
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

    async function loadWeek(keepSelection = false) {
        if (!keepSelection) {
            setSelected(new Set())
        }

        try {
            // Asks for the manager's own week in the overview and takes only the
            // shifts from it, since the constraints for everyone come from the
            // second call.
            const query = isManager && employeeId !== ALL ? `?employeeId=${employeeId}` : ''

            const calls = [api.get(`/api/schedules/${week.id}/my-week${query}`)]

            if (employeeId === ALL) {
                calls.push(api.get(`/api/shift-preferences?scheduleId=${week.id}`))
            }

            const [weekData, preferences] = await Promise.all(calls)

            setMyWeek(weekData)
            setAllPreferences(preferences ?? [])
            setError(null)
        } catch {
            setError('לא הצלחנו לטעון את המשמרות')
        }
    }

    const selectedShifts = myWeek
        ? myWeek.shifts.filter((shift) => selected.has(shift.shiftId))
        : []

    // The type applies to every selected shift, so one that's already right is
    // skipped rather than deleted and rewritten.
    async function applyType(type) {
        setBusy(true)

        try {
            // Sends one request per selected shift. The server takes one preference
            // at a time.
            for (const shift of selectedShifts) {
                const current = shift.preferenceType ?? null

                if (current === type) {
                    continue
                }

                if (type === null) {
                    await api.delete(
                        `/api/shift-preferences/${shift.preferenceId}?version=${shift.preferenceVersion}`)
                } else if (current === null) {
                    await api.post('/api/shift-preferences', {
                        shiftId: shift.shiftId,
                        type,
                        // A manager sends the employee id. An employee doesn't, and
                        // the server takes it from the token.
                        employeeId: isManager ? employeeId : undefined,
                    })
                } else {
                    await api.put(`/api/shift-preferences/${shift.preferenceId}`, {
                        type,
                        reason: shift.preferenceReason,
                        version: shift.preferenceVersion,
                    })
                }
            }

            await loadWeek(true)
        } catch (err) {
            // 409 and 404 both mean someone else changed or removed a constraint
            // since this screen loaded. Same as the other screens, the manager
            // reloads by hand.
            setError(err.code === 'WRONG_STATUS'
                ? 'תקופת הגשת האילוצים לשבוע זה נסגרה'
                : err.status === 409 || err.status === 404
                    ? 'האילוצים שונו במקביל. רענן ונסה שוב'
                    : 'השמירה נכשלה')
        } finally {
            setBusy(false)
        }
    }

    // Saved when the selection changes or the panel closes.
    async function applyReason(reason, shifts) {
        const withPreference = shifts.filter((shift) => shift.preferenceId)

        if (withPreference.length === 0) {
            return
        }

        setBusy(true)

        try {
            for (const shift of withPreference) {
                if ((shift.preferenceReason ?? '') === reason) {
                    continue
                }

                await api.put(`/api/shift-preferences/${shift.preferenceId}`, {
                    type: shift.preferenceType,
                    reason: reason || null,
                    version: shift.preferenceVersion,
                })
            }

            await loadWeek(true)
        } catch (err) {
            setError(err.status === 409 || err.status === 404
                ? 'האילוצים שונו במקביל. רענן ונסה שוב'
                : 'שמירת הסיבה נכשלה')
        } finally {
            setBusy(false)
        }
    }

    if (loading) {
        return <p className="notice">טוען…</p>
    }

    if (weeks.length === 0) {
        return <p className="notice">אין עדיין שבועות במערכת.</p>
    }

    const overview = employeeId === ALL

    // Disables edit in the all view constraints.
    // if changes are required, manager can select the specific employee
    // and make the changes over there
    const editableStatus = myWeek?.status === 'COLLECTING' || myWeek?.status === 'DRAFT'
    // A manager can still edit after the submission window closed.
    const canEdit = !overview && editableStatus && (myWeek?.submissionOpen || isManager)

    const byShift = new Map()

    for (const preference of allPreferences) {
        const list = byShift.get(preference.shiftId) ?? []
        list.push(preference)
        byShift.set(preference.shiftId, list)
    }

    return (
        <>
            <div className="page-head">
                <h1>אילוצים</h1>
            </div>

            <div className="week-row">
                <WeekPicker weeks={weeks} current={week} onChange={setWeek}>
                    {myWeek && (
                        <span className="week-status">
                            {myWeek.submissionOpen ? 'פתוח להגשה' : 'ההגשה נסגרה'}
                        </span>
                    )}
                </WeekPicker>

                {isManager && (
                    <div className="week-actions">
                        <select value={employeeId}
                                onChange={(e) => setEmployeeId(
                                    e.target.value === ALL ? ALL : Number(e.target.value))}>
                            <option value={ALL}>כל העובדים</option>
                            {employees.map((employee) => (
                                <option key={employee.id} value={employee.id}>
                                    {employee.fullName}
                                </option>
                            ))}
                        </select>
                    </div>
                )}
            </div>

            {error && <p className="error">{error}</p>}

            {myWeek && (
                <ShiftGrid
                    shifts={myWeek.shifts}
                    weekStart={myWeek.weekStart}
                    selected={selected}
                    onSelectionChange={canEdit ? setSelected : undefined}
                    renderCell={(shift) => (
                        overview
                            ? <ShiftConstraints
                                preferences={byShift.get(shift.shiftId) ?? []} />
                            : (
                                <div className={`pref pref-${shift.preferenceType ?? 'none'}`}>
                                    <span>{LABELS[shift.preferenceType] ?? 'יכול'}</span>
                                    {shift.preferenceReason && (
                                        <span className="pref-reason" title={shift.preferenceReason}>
                                             {shift.preferenceReason}
                                    </span>
                                    )}
                                </div>
                            )
                    )}
                />
            )}

            {canEdit && selected.size > 0 && (
                <SelectionPanel
                    shifts={selectedShifts}
                    busy={busy}
                    onType={applyType}
                    onReason={applyReason}
                />
            )}

            {overview && (
                <p className="hint">
                    מוצגים האילוצים שהוגשו לשבוע זה. לעריכה יש לבחור עובד.
                </p>
            )}
            {canEdit && selected.size === 0 && (
                <p className="hint">
                    בחר משמרת או גרור על כמה כדי לקבוע אילוץ.
                </p>
            )}
        </>
    )
}

function SelectionPanel({ shifts, busy, onType, onReason }) {
    const [reason, setReason] = useState(sharedReason(shifts))

    // The cleanup below runs after the state already changed, so it reads the
    // values from refs.
    const reasonRef = useRef(reason)
    const shiftsRef = useRef(shifts)

    useEffect(() => {
        reasonRef.current = reason
        shiftsRef.current = shifts
    }, [reason, shifts])

    // The selected ids joined into a string. The save below depends on it,
    // so it runs when the selection changes and not on every render.
    const selectionKey = shifts.map((shift) => shift.shiftId).join()

    // Joins the ids with their saved reasons, so the field also follows a reason
    // that was saved from another screen and came back with a reload.
    const reasonKey = shifts
        .map((shift) => `${shift.shiftId}:${shift.preferenceReason ?? ''}`)
        .join()

    // Saved when the selection goes away - either the panel closes or a
    // different shift is picked. Keyed on the selection so moving from one
    // shift to another writes the reason before the field is refilled.
    useEffect(() => {
        return () => {
            const current = reasonRef.current
            const target = shiftsRef.current

            if (current !== sharedReason(target)) {
                onReason(current.trim(), target)
            }
        }
    }, [selectionKey])

    // Refills the field when the selection changes or a reload brings a different
    // saved reason, so closing the panel never writes an old reason back.
    useEffect(() => {
        setReason(sharedReason(shifts))
    }, [reasonKey])

    const current = sharedType(shifts)
    const anyWithPreference = shifts.some((shift) => shift.preferenceId)

    return (
        <div className="panel">
            <div className="panel-head">
                <strong>
                    {shifts.length === 1
                        ? describe(shifts[0])
                        : `${shifts.length} משמרות נבחרו`}
                </strong>
            </div>
            <div className="panel-choices">
                {TYPES.map((type) => (
                    <button
                        key={type.label}
                        disabled={busy}
                        className={current === type.value ? 'choice is-current' : 'choice'}
                        onClick={() => onType(type.value)}
                    >
                        {type.label}
                    </button>
                ))}
            </div>

            {anyWithPreference && (
                <label className="panel-reason">
                    <span className="field-label">סיבה</span>
                    <input
                        value={reason}
                        disabled={busy}
                        /* Cuts the reason at 255 characters, the same limit the
                           server takes. */
                        maxLength={255}
                        placeholder="רשות"
                        onChange={(e) => setReason(e.target.value)}
                    />
                </label>
            )}
        </div>
    )
}

function describe(shift) {
    const [year, month, day] = shift.shiftDate.split('-')
    return `${shift.shiftTypeName} · ${Number(day)}/${Number(month)}/${year}`
}

// Only shows a type as current when every selected shift agrees.
function sharedType(shifts) {
    const first = shifts[0]?.preferenceType ?? null

    return shifts.every((shift) => (shift.preferenceType ?? null) === first)
        ? first
        : undefined
}

// Returns empty when the selected shifts have different reasons. Saving then
// doesn't copy one reason onto all of them.
function sharedReason(shifts) {
    const first = shifts[0]?.preferenceReason ?? ''

    return shifts.every((shift) => (shift.preferenceReason ?? '') === first) ? first : ''
}

// Shows the constraints entered by all employees of thw week.
// Reasoning is that manager should have an all out view for the constaints
// and their reasoning to make sure nothing stands out and he is allowed to continue
// with building the schedule
function ShiftConstraints({ preferences }) {
    if (preferences.length === 0) {
        return null
    }

    return (
        <div className="constraint-list">
            {preferences.map((preference) => (
                <div key={preference.id}
                     className={`constraint constraint-${preference.type}`}>
                    <span className="constraint-name">{preference.employeeName}</span>
                    {preference.reason && (
                        <span className="constraint-reason" title={preference.reason}>
                                {preference.reason}
                        </span>
                    )}
                </div>
            ))}
        </div>
    )
}
